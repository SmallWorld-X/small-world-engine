package creativeworld;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.io.IOException;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import static creativeworld.Protocol.*;

/** 同一世界向前提交的本地账本。所有读取都重放事件，所有写入都在同一文件锁内校验。 */
public final class WorldStore {
    private static final Map<String, ReentrantLock> LOCAL_LOCKS = new ConcurrentHashMap<>();
    private static final Map<RequestKey, RequestCoordination> REQUESTS = new ConcurrentHashMap<>();
    private static final Set<String> PROPOSAL_FIELDS = Set.of("schemaVersion", "worldId", "requestId", "expectedRevision", "actor", "reason", "effects");
    private static final String[] IDENTITY_FIELDS = {"schemaVersion", "worldId", "requestId", "expectedRevision", "actor"};
    private final Path file;
    private final Map<ModuleKey, ActionModule> modules = new ConcurrentHashMap<>();

    public WorldStore(Path file) {
        Path normalized = Objects.requireNonNull(file).toAbsolutePath().normalize();
        try {
            this.file = Files.exists(normalized) ? normalized.toRealPath()
                : Files.exists(normalized.getParent()) ? normalized.getParent().toRealPath().resolve(normalized.getFileName()) : normalized;
        } catch (IOException invalid) { throw new IllegalArgumentException("世界存储路径无法解析", invalid); }
        register(BuiltinModules.ask()); register(BuiltinModules.travel()); register(BuiltinModules.observe());
        register(new MessageResponseModule());
        register(new MessageStateResponseModule());
    }

    public JsonNode initialize(JsonNode seed) throws IOException {
        ObjectNode confirmed = canonicalObject(object(seed, Set.of("schemaVersion", "worldId", "title", "places", "characters", "facts"), Set.of("relationships", "routes", "entities", "laws", "worldTime")));
        WorldState state = new WorldState(confirmed);
        Files.createDirectories(file.getParent());
        return locked(() -> {
            if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) throw bad("世界文件已经存在，不能重置或覆盖");
            ObjectNode document = JSON.createObjectNode().put("schemaVersion", 1);
            document.set("seed", confirmed); document.putArray("events");
            save(document); return state.inspect();
        });
    }

    public JsonNode inspect() throws IOException { return locked(() -> load().state.inspect()); }
    public Path path() { return file; }
    public JsonNode view(String actor) throws IOException {
        if (actor == null || actor.isBlank()) throw bad("视角不能为空");
        return locked(() -> load().state.view(actor));
    }
    public JsonNode viewAt(String actor, int revision) throws IOException {
        if (actor == null || actor.isBlank()) throw bad("视角不能为空");
        if (revision < 0) throw bad("历史版本须为非负整数");
        return locked(() -> {
            Loaded loaded = load();
            if (revision > loaded.state.revision) throw bad("历史版本尚不存在");
            WorldState historical = new WorldState(loaded.document.get("seed"));
            for (JsonNode event : loaded.document.path("events")) {
                if (historical.revision == revision) break;
                historical.commit(historical.validateProposal(event.get("proposal")));
            }
            return historical.view(actor);
        });
    }
    public JsonNode history() throws IOException {
        return locked(() -> {
            Loaded loaded = load(); ObjectNode result = loaded.state.metadata();
            result.set("events", loaded.document.path("events").deepCopy()); return result;
        });
    }
    public JsonNode apply(JsonNode proposal) throws IOException {
        ObjectNode frozen = canonicalObject(object(proposal, PROPOSAL_FIELDS, Set.of("requiresKnown")));
        return locked(() -> apply(load(), frozen, null));
    }

    public void register(ActionModule module) {
        Objects.requireNonNull(module, "行为模块不能为空");
        String id = module.id();
        if (id == null || id.isBlank() || id.startsWith("@")) throw bad("模块标识无效");
        ModuleKey identity = new ModuleKey(id, moduleVersion(module.version()));
        if (modules.putIfAbsent(identity, module) != null) throw bad("行为模块标识与版本已经注册");
    }
    public JsonNode step(String moduleId, JsonNode input) throws IOException {
        return step(submission(moduleId, null, input));
    }
    public JsonNode step(String moduleId, String moduleVersion, JsonNode input) throws IOException {
        return step(submission(moduleId, moduleVersion(moduleVersion), input));
    }
    private JsonNode step(ObjectNode submission) throws IOException {
        return coordinated(submission, coordination -> commitPrepared(prepare(submission, coordination)));
    }

    /** 仅生成候选；模块只收到冻结的人物投影和输入，推导期间不持有世界文件锁。 */
    public JsonNode propose(String moduleId, JsonNode input) throws IOException {
        return propose(submission(moduleId, null, input));
    }
    public JsonNode propose(String moduleId, String moduleVersion, JsonNode input) throws IOException {
        return propose(submission(moduleId, moduleVersion(moduleVersion), input));
    }
    private JsonNode propose(ObjectNode submission) throws IOException {
        return coordinated(submission, coordination -> prepare(submission, coordination));
    }
    public JsonNode receiptFor(String moduleId, JsonNode input) throws IOException {
        return receiptFor(submission(moduleId, null, input));
    }
    public JsonNode receiptFor(String moduleId, String moduleVersion, JsonNode input) throws IOException {
        return receiptFor(submission(moduleId, moduleVersion(moduleVersion), input));
    }
    private JsonNode receiptFor(ObjectNode submission) throws IOException {
        return locked(() -> committedReceipt(load(), submission));
    }

    /** 准备记录不是签名；正式提交仍重新校验身份、版本、权限及整个事务的效果。 */
    public JsonNode commitPrepared(JsonNode prepared) throws IOException {
        if (prepared == null || !prepared.isObject()) throw bad("准备记录须为对象");
        ObjectNode frozen = canonicalObject(prepared);
        String status = text(frozen, "status");
        if (status.equals("prepared")) object(frozen, "schemaVersion", "status", "submission", "proposal");
        else if (status.equals("alreadyCommitted")) object(frozen, "schemaVersion", "status", "submission", "receipt");
        else throw bad("不支持的准备状态");
        version(frozen);
        ObjectNode submission = canonicalObject(validateSubmissionInput(frozen.get("submission")));
        JsonNode input = submission.get("input");
        ObjectNode proposal = null;
        if (status.equals("prepared")) {
            proposal = freezeProposal(frozen.get("proposal"));
            validateSubmission(submission, proposal);
        } else {
            JsonNode supplied = object(frozen.get("receipt"), "schemaVersion", "worldId", "requestId", "revision", "status");
            version(supplied); integer(supplied, "revision");
            if (!text(supplied, "status").equals("committed") || !text(supplied, "worldId").equals(text(input, "worldId"))
                || !text(supplied, "requestId").equals(text(input, "requestId"))) throw bad("准备回执与行为身份不一致");
        }
        ObjectNode incoming = proposal;
        return locked(() -> {
            Loaded loaded = load();
            ObjectNode original = committedReceipt(loaded, submission);
            if (original != null) return original;
            if (incoming == null) throw bad("准备回执没有对应的正式提交");
            return apply(loaded, incoming, submission);
        });
    }

    private ObjectNode prepare(ObjectNode submission, RequestCoordination coordination) throws IOException {
        JsonNode input = submission.get("input");
        Preparation snapshot = locked(() -> {
            Loaded loaded = load(); ObjectNode committed = committedReceipt(loaded, submission);
            if (committed != null) return new Preparation(null, committed);
            if (integer(input, "expectedRevision") != loaded.state.revision) throw bad("行为输入版本已过期");
            return new Preparation(loaded.state.view(text(input, "actor")).deepCopy(), null);
        });
        if (snapshot.receipt != null) {
            ObjectNode result = envelope("alreadyCommitted", submission); result.set("receipt", snapshot.receipt); return result;
        }
        if (coordination.inferring) throw bad("相同行为请求不能在推导中递归调用自身");
        if (coordination.prepared != null) {
            if (!sameSubmission(submission, coordination.prepared.get("submission"))) throw bad("并发请求标识被用于不同的行为输入或版本");
            ObjectNode result = coordination.prepared.deepCopy(); result.set("submission", submission.deepCopy()); return result;
        }
        ModuleKey identity = new ModuleKey(text(submission, "module"), moduleVersion(submission));
        ActionModule module = modules.get(identity);
        if (module == null) throw bad("行为模块未注册：" + identity.id + " / " + identity.version);
        ObjectNode proposal;
        coordination.inferring = true;
        try {
            validateModuleIdentity(module, identity);
            proposal = freezeProposal(module.propose(snapshot.actorView, input.deepCopy()));
            validateModuleIdentity(module, identity);
        }
        finally { coordination.inferring = false; }
        validateSubmission(submission, proposal);
        ObjectNode result = envelope("prepared", submission); result.set("proposal", proposal);
        coordination.prepared = result.deepCopy();
        return result;
    }

    private static ObjectNode submission(String moduleId, String explicitVersion, JsonNode input) {
        if (moduleId == null || moduleId.isBlank() || moduleId.startsWith("@")) throw bad("模块标识无效");
        if (input == null || !input.isObject()) throw bad("行为输入须为对象");
        ObjectNode result = JSON.createObjectNode().put("kind", "step").put("module", moduleId);
        if (explicitVersion != null) result.put("moduleVersion", moduleVersion(explicitVersion));
        result.set("input", canonical(input)); validateSubmissionInput(result); return result;
    }
    private static void validateModuleIdentity(ActionModule module, ModuleKey identity) {
        if (!identity.id.equals(module.id()) || !identity.version.equals(moduleVersion(module.version())))
            throw bad("行为模块在注册后改变了标识或版本");
    }
    private static boolean sameSubmission(JsonNode left, JsonNode right) {
        if (left == null || right == null) return left == right;
        ObjectNode normalizedLeft = canonicalObject(validateSubmissionInput(left));
        ObjectNode normalizedRight = canonicalObject(validateSubmissionInput(right));
        normalizedLeft.put("moduleVersion", moduleVersion(left));
        normalizedRight.put("moduleVersion", moduleVersion(right));
        return normalizedLeft.equals(normalizedRight);
    }
    private static ObjectNode freezeProposal(JsonNode proposal) {
        ObjectNode result = canonicalObject(object(proposal, PROPOSAL_FIELDS, Set.of("requiresKnown")));
        version(result); text(result, "worldId"); id(result, "requestId"); integer(result, "expectedRevision");
        text(result, "actor"); text(result, "reason");
        if (array(result, "effects").isEmpty()) throw bad("提案至少需要一个效果");
        if (result.has("requiresKnown")) array(result, "requiresKnown");
        return result;
    }
    private static ObjectNode envelope(String status, ObjectNode submission) {
        ObjectNode result = JSON.createObjectNode().put("schemaVersion", 1).put("status", status);
        result.set("submission", submission.deepCopy()); return result;
    }
    private static ObjectNode committedReceipt(Loaded loaded, ObjectNode submission) {
        JsonNode input = submission.get("input");
        if (!text(input, "worldId").equals(loaded.state.worldId)) throw bad("行为输入所属世界不一致");
        String requestId = text(input, "requestId"); ObjectNode original = loaded.requests.get(requestId);
        if (original == null) return null;
        if (!sameSubmission(submission, original.get("submission"))) throw bad("请求标识被用于不同的行为提交、版本或提案");
        return receipt(loaded.state.worldId, requestId, integer(original, "revision"));
    }

    private JsonNode coordinated(ObjectNode submission, RequestOperation operation) throws IOException {
        RequestKey key = new RequestKey(file.getParent().toRealPath().resolve(file.getFileName()).toString().toLowerCase(Locale.ROOT),
            text(submission, "module"), text(submission.get("input"), "requestId"));
        RequestCoordination coordination = REQUESTS.compute(key, (ignored, existing) -> {
            RequestCoordination next = existing == null ? new RequestCoordination() : existing; next.users++; return next;
        });
        coordination.lock.lock();
        try { return operation.run(coordination); }
        finally {
            coordination.lock.unlock();
            REQUESTS.compute(key, (ignored, existing) -> --existing.users == 0 ? null : existing);
        }
    }

    private JsonNode apply(Loaded loaded, JsonNode incoming, ObjectNode submission) throws IOException {
        ObjectNode proposal = loaded.state.validateProposal(incoming);
        String requestId = text(proposal, "requestId");
        ObjectNode original = loaded.requests.get(requestId);
        if (original != null) {
            if (!sameSubmission(original.get("submission"), submission)) throw bad("请求标识不能跨提案、行为版本与行为提交复用");
            if (!original.path("proposal").equals(proposal)) throw bad("请求标识被用于不同的提案");
            return receipt(loaded.state.worldId, requestId, integer(original, "revision"));
        }
        WorldState candidate = loaded.state.copy(); candidate.commit(proposal);
        ObjectNode event = JSON.createObjectNode().put("revision", candidate.revision); event.set("proposal", proposal);
        if (candidate.time != null) event.set("worldTime", candidate.time.json());
        if (submission != null) { validateSubmission(submission, proposal); event.set("submission", submission.deepCopy()); }
        ((ArrayNode) loaded.document.path("events")).add(event);
        save(loaded.document);
        return receipt(candidate.worldId, requestId, candidate.revision);
    }

    private Loaded load() throws IOException {
        if (!Files.isRegularFile(file)) throw bad("世界文件不存在或不是普通文件");
        JsonNode value = JSON.readTree(file.toFile()); object(value, "schemaVersion", "seed", "events"); version(value);
        WorldState state = new WorldState(value.get("seed"));
        LinkedHashMap<String, ObjectNode> requests = new LinkedHashMap<>();
        for (JsonNode event : array(value, "events")) {
            object(event, Set.of("revision", "proposal"), Set.of("submission", "worldTime"));
            if ((state.time != null) != event.has("worldTime")) throw bad("历史事件时钟与世界的时间配置不一致");
            if (integer(event, "revision") != Math.addExact(state.revision, 1)) throw bad("世界历史版本不连续");
            ObjectNode proposal = state.validateProposal(event.get("proposal"));
            String requestId = text(proposal, "requestId");
            if (requests.containsKey(requestId)) throw bad("世界历史含重复请求事件");
            if (event.has("submission")) validateSubmission(event.get("submission"), proposal);
            state.commit(proposal);
            if (state.time != null) state.time.verify(event.get("worldTime"));
            ObjectNode confirmedEvent = (ObjectNode) event.deepCopy(); confirmedEvent.set("proposal", proposal);
            if (event.has("submission")) confirmedEvent.set("submission", canonical(event.get("submission")));
            requests.put(requestId, confirmedEvent);
        }
        return new Loaded((ObjectNode) value.deepCopy(), state, requests);
    }
    private static ObjectNode validateSubmissionInput(JsonNode submission) {
        ObjectNode result = object(submission, Set.of("kind", "module", "input"), Set.of("moduleVersion"));
        if (!text(submission, "kind").equals("step")) throw bad("历史行为提交类型不支持");
        id(submission, "module"); moduleVersion(submission); JsonNode input = submission.get("input");
        if (!input.isObject()) throw bad("历史行为输入须为对象");
        version(input); text(input, "worldId"); id(input, "requestId"); integer(input, "expectedRevision"); text(input, "actor");
        canonical(input); return result;
    }
    private static void validateSubmission(JsonNode submission, JsonNode proposal) {
        JsonNode input = validateSubmissionInput(submission).get("input");
        for (String key : IDENTITY_FIELDS)
            if (!canonical(input.get(key)).equals(canonical(proposal.get(key)))) throw bad("历史行为提交与提案身份不一致");
    }
    private static ObjectNode receipt(String worldId, String requestId, int revision) {
        return JSON.createObjectNode().put("schemaVersion", 1).put("worldId", worldId).put("requestId", requestId)
            .put("revision", revision).put("status", "committed");
    }
    private record Loaded(ObjectNode document, WorldState state, Map<String, ObjectNode> requests) {}
    private record Preparation(JsonNode actorView, ObjectNode receipt) {}
    private record ModuleKey(String id, String version) {}
    private record RequestKey(String file, String module, String requestId) {}
    private static final class RequestCoordination {
        final ReentrantLock lock = new ReentrantLock();
        int users;
        boolean inferring;
        ObjectNode prepared;
    }
    @FunctionalInterface private interface RequestOperation { JsonNode run(RequestCoordination coordination) throws IOException; }
    @FunctionalInterface private interface Operation<T> { T run() throws IOException; }
    @SuppressWarnings("try") // FileLock 的持有期就是事务边界，无需读取其字段。
    private <T> T locked(Operation<T> operation) throws IOException {
        if (!Files.isDirectory(file.getParent())) throw bad("世界文件目录不存在");
        Path physical = file.getParent().toRealPath().resolve(file.getFileName());
        ReentrantLock local = LOCAL_LOCKS.computeIfAbsent(physical.toString().toLowerCase(Locale.ROOT), ignored -> new ReentrantLock());
        local.lock();
        try (FileChannel channel = FileChannel.open(physical.resolveSibling(physical.getFileName() + ".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignored = channel.lock()) {
            return operation.run();
        } finally { local.unlock(); }
    }
    private void save(ObjectNode document) throws IOException {
        Path temporary = Files.createTempFile(file.getParent(), "." + file.getFileName() + "-", ".tmp");
        try {
            JSON.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), document);
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            throw new IOException("文件系统不支持原子替换，世界文件未按不安全方式写入", unsupported);
        } finally { Files.deleteIfExists(temporary); }
    }
}
