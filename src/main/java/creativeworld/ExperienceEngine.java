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

/** 演绎阶段保存于独立记录；只有既有世界提交器可以改变正式历史。 */
public final class ExperienceEngine {
    private static final Set<String> IDENTITY = Set.of("schemaVersion", "worldId", "requestId", "expectedRevision", "actor");
    private static final Map<String, ReentrantLock> RECORD_LOCKS = new ConcurrentHashMap<>();
    private final Map<String, ComponentBinding> components = new LinkedHashMap<>();

    public ExperienceEngine() { this(true); }
    public ExperienceEngine(boolean defaults) {
        if (defaults) {
            for (ExperienceComponent component : BuiltinExperienceComponents.all()) register(component);
            for (ExperienceComponent component : KnowledgeGoalComponents.all()) register(component);
            for (ExperienceComponent component : MessageReactionComponents.all()) register(component);
            for (ExperienceComponent component : MessageReactionComponents.stateResponses()) register(component);
        }
    }
    public synchronized void register(ExperienceComponent component) {
        Objects.requireNonNull(component, "演绎组件不能为空");
        String id = component.id(), version = component.version();
        String key = key(id, version);
        ComponentBinding binding = new ComponentBinding(id, version,
            Objects.requireNonNull(component.stage(), "组件阶段不能为空"), component);
        if (components.putIfAbsent(key, binding) != null) throw bad("演绎组件版本已经注册");
    }

    public ObjectNode run(WorldStore world, JsonNode incoming, Path recordFile) throws IOException {
        Objects.requireNonNull(world, "正式世界不能为空");
        ObjectNode request = canonicalObject(object(incoming, "schemaVersion", "worldId", "requestId",
            "expectedRevision", "actor", "intent", "scenario", "components"));
        version(request); id(request, "requestId"); text(request, "worldId"); integer(request, "expectedRevision");
        id(request, "actor"); text(request, "intent");
        if (!request.path("scenario").isObject()) throw bad("scenario必须是对象");
        data(request);
        List<ComponentBinding> pipeline = pipeline(request);
        Path target = Objects.requireNonNull(recordFile).toAbsolutePath().normalize();
        Files.createDirectories(target.getParent());
        target = target.getParent().toRealPath().resolve(target.getFileName());
        if (target.toString().equalsIgnoreCase(world.path().toString())
            || target.toString().equalsIgnoreCase(world.path().resolveSibling(world.path().getFileName() + ".lock").toString())
            || (Files.exists(target) && Files.isSameFile(target, world.path())))
            throw bad("演绎记录不能覆盖正式世界");
        Path sidecar = target.resolveSibling(target.getFileName() + ".lock");
        if (Files.isSymbolicLink(target) || Files.isSymbolicLink(sidecar)
            || (Files.exists(sidecar) && Files.isSameFile(sidecar, world.path())))
            throw bad("演绎记录或锁文件不能链接到其他存档");
        Path destination = target;
        ReentrantLock local = RECORD_LOCKS.computeIfAbsent(destination.toString().toLowerCase(Locale.ROOT), ignored -> new ReentrantLock());
        local.lock();
        try { return lockedRun(world, request, pipeline, destination); }
        finally { local.unlock(); }
    }

    @SuppressWarnings("try")
    private ObjectNode lockedRun(WorldStore world, ObjectNode request, List<ComponentBinding> pipeline, Path file) throws IOException {
        try (FileChannel channel = FileChannel.open(file.resolveSibling(file.getFileName() + ".lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignored = channel.lock()) {
            ObjectNode record;
            if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                record = object(JSON.readTree(file.toFile()), Set.of("schemaVersion", "kind", "worldFile", "request", "initialView", "stages", "status"),
                    Set.of("prepared", "receipt", "finalView", "error"));
                version(record);
                if (!text(record, "kind").equals("world-experience")
                    || !text(record, "worldFile").equals(world.path().toString())
                    || !canonical(record.get("request")).equals(request))
                    throw bad("演绎记录属于另一输入、组件版本或世界，请建立新尝试");
                array(record, "stages");
                object(record.get("initialView"), Set.of("schemaVersion", "worldId", "title", "revision", "actor", "self", "nearby", "knowledge", "beliefs", "relationships"), Set.of("worldTime"));
                checkInitial(record.get("initialView"), request);
                if (!canonical(record.get("initialView")).equals(canonical(world.viewAt(text(request, "actor"), integer(request, "expectedRevision")))))
                    throw bad("演绎起始视图与正式历史不一致");
                ObjectNode priorOutputs = restoredOutputs(record, pipeline);
                String status = text(record, "status");
                if (!Set.of("running", "failed", "feedbackFailed", "completed", "needsInput", "blocked", "satisfied").contains(status))
                    throw bad("不支持的演绎记录状态");
                String terminal = null;
                for (JsonNode output : priorOutputs) if (!text(output, "status").equals("ready")) terminal = text(output, "status");
                if (terminal != null) {
                    if (status.equals("completed")) throw bad("完成记录包含尚未就绪的阶段");
                    if (priorOutputs.has("feedback")) verifyCompleted(world, request, record, priorOutputs);
                    else if (record.has("prepared") || record.has("receipt") || record.has("finalView"))
                        throw bad("未就绪阶段不能附带正式提交结果");
                    record.put("status", terminal); save(file, record); return canonicalObject(record);
                }
                if (Set.of("completed", "needsInput", "blocked", "satisfied").contains(status)) {
                    if (!status.equals("completed")) throw bad("停止记录缺少对应的阶段缺口");
                    if (status.equals("completed") && priorOutputs.size() != pipeline.size()) throw bad("完成记录缺少完整阶段结果");
                    verifyCompleted(world, request, record, priorOutputs);
                    return canonicalObject(record);
                }
                if (!Set.of("running", "failed", "feedbackFailed").contains(status)) throw bad("不支持的演绎记录状态");
            } else {
                ObjectNode initial = (ObjectNode) world.view(text(request, "actor"));
                checkInitial(initial, request);
                record = JSON.createObjectNode().put("schemaVersion", 1).put("kind", "world-experience")
                    .put("worldFile", world.path().toString()).put("status", "running");
                record.set("request", request.deepCopy()); record.set("initialView", initial.deepCopy()); record.putArray("stages");
                save(file, record);
            }
            ObjectNode outputs = restoredOutputs(record, pipeline);
            record.put("status", "running"); record.remove("error");
            try {
                for (ComponentBinding binding : pipeline) {
                    String stage = binding.stage.key();
                    if (outputs.has(stage)) continue;
                    ObjectNode actorView = (ObjectNode) record.get("initialView");
                    ObjectNode receipt = JSON.createObjectNode();
                    ObjectNode result;
                    try {
                        validateComponentIdentity(binding);
                        if (binding.stage == ExperienceComponent.Stage.FEEDBACK) {
                            ensureCommitted(world, request, record, outputs, file);
                            receipt = (ObjectNode) record.get("receipt");
                            actorView = (ObjectNode) world.viewAt(text(request, "actor"), integer(receipt, "revision"));
                            record.set("finalView", actorView.deepCopy()); save(file, record);
                        }
                        result = binding.component.run(new ExperienceComponent.Context(request, actorView, outputs, receipt));
                        validateComponentIdentity(binding);
                        result = validateResult(binding.stage, result, outputs);
                        if (binding.stage == ExperienceComponent.Stage.FEEDBACK) {
                            if (result.has("receipt") && !canonical(result.get("receipt")).equals(canonical(receipt)))
                                throw bad("反馈组件不能伪造正式回执");
                            if (result.has("after") && !canonical(result.get("after")).equals(canonical(actorView)))
                                throw bad("反馈组件不能改写实际人物状态");
                            result.set("receipt", receipt.deepCopy()); result.set("after", actorView.deepCopy());
                        }
                    } catch (RuntimeException failure) {
                        ObjectNode failed = array(record, "stages").addObject().put("stage", stage)
                            .put("componentId", binding.id).put("componentVersion", binding.version).put("status", "failed");
                        failed.set("error", failure(failure));
                        throw failure;
                    }
                    ObjectNode completed = array(record, "stages").addObject().put("stage", stage)
                        .put("componentId", binding.id).put("componentVersion", binding.version).put("status", "completed");
                    completed.set("output", result.deepCopy()); outputs.set(stage, result.deepCopy()); save(file, record);
                    if (!text(result, "status").equals("ready")) {
                        record.put("status", text(result, "status")); save(file, record); return canonicalObject(record);
                    }
                }
                verifyCompleted(world, request, record, outputs);
                record.put("status", "completed"); save(file, record); return canonicalObject(record);
            } catch (RuntimeException | IOException failure) {
                record.put("status", record.has("receipt") ? "feedbackFailed" : "failed");
                record.set("error", failure(failure)); save(file, record); throw failure;
            }
        }
    }

    private void ensureCommitted(WorldStore world, ObjectNode request, ObjectNode record, ObjectNode outputs, Path file) throws IOException {
        JsonNode action = outputs.path("resolve").path("action");
        ObjectNode input = actionInput(request, action);
        if (!record.has("prepared")) {
            record.set("prepared", action.has("moduleVersion")
                ? world.propose(text(action, "module"), moduleVersion(action), input)
                : world.propose(text(action, "module"), input));
            save(file, record);
        }
        checkPrepared(record.get("prepared"), action, input);
        if (record.has("receipt")) {
            JsonNode original = world.receiptFor(text(action, "module"), moduleVersion(action), input);
            if (original == null || !canonical(original).equals(canonical(record.get("receipt"))))
                throw bad("恢复记录的回执没有对应正式事件");
        }
        JsonNode receipt = world.commitPrepared(record.get("prepared"));
        if (record.has("receipt") && !canonical(receipt).equals(canonical(record.get("receipt")))) throw bad("恢复回执与原回执不一致");
        record.set("receipt", receipt.deepCopy()); save(file, record);
    }

    private static ObjectNode actionInput(ObjectNode request, JsonNode action) {
        ObjectNode input = canonicalObject(action.get("arguments"));
        for (String field : IDENTITY) input.set(field, request.get(field).deepCopy());
        return input;
    }
    private static void checkPrepared(JsonNode prepared, JsonNode action, ObjectNode input) {
        JsonNode submission = prepared.path("submission");
        if (!submission.path("kind").asText().equals("step")
            || !submission.path("module").asText().equals(text(action, "module"))
            || !moduleVersion(submission).equals(moduleVersion(action))
            || !canonical(submission.path("input")).equals(input))
            throw bad("恢复的准备提案与已选行动不一致");
    }
    private static void verifyCompleted(WorldStore world, ObjectNode request, ObjectNode record, ObjectNode outputs) throws IOException {
        if (!record.has("prepared") || !record.has("receipt") || !record.has("finalView") || !outputs.has("feedback"))
            throw bad("完成记录缺少准备提案、回执或反馈视图");
        JsonNode action = outputs.path("resolve").path("action");
        checkPrepared(record.get("prepared"), action, actionInput(request, action));
        // 用查询核对完成记录，不能让一份伪造的完成记录在回读时首次提交事件。
        JsonNode receipt = world.receiptFor(text(action, "module"), moduleVersion(action), actionInput(request, action));
        if (receipt == null) throw bad("完成记录没有对应正式事件");
        JsonNode view = world.viewAt(text(request, "actor"), integer(receipt, "revision"));
        JsonNode feedback = outputs.get("feedback");
        if (!canonical(receipt).equals(canonical(record.get("receipt")))
            || !canonical(view).equals(canonical(record.get("finalView")))
            || !canonical(receipt).equals(canonical(feedback.path("receipt")))
            || !canonical(view).equals(canonical(feedback.path("after"))))
            throw bad("完成记录的反馈与正式事件不一致");
    }

    private synchronized List<ComponentBinding> pipeline(ObjectNode request) {
        ArrayNode config = array(request, "components");
        if (config.size() != ExperienceComponent.Stage.values().length) throw bad("演绎必须明确配置五个阶段");
        EnumMap<ExperienceComponent.Stage, ComponentBinding> selected = new EnumMap<>(ExperienceComponent.Stage.class);
        for (JsonNode row : config) {
            object(row, "stage", "id", "version");
            ExperienceComponent.Stage stage;
            try { stage = ExperienceComponent.Stage.valueOf(text(row, "stage").toUpperCase(Locale.ROOT)); }
            catch (IllegalArgumentException invalid) { throw bad("未知演绎阶段"); }
            ComponentBinding binding = components.get(key(text(row, "id"), text(row, "version")));
            if (binding == null) throw bad("演绎组件版本未注册：" + text(row, "id"));
            if (binding.stage != stage) throw bad("组件与配置阶段不符");
            if (selected.putIfAbsent(stage, binding) != null) throw bad("演绎阶段重复");
        }
        List<ComponentBinding> result = new ArrayList<>();
        for (ExperienceComponent.Stage stage : ExperienceComponent.Stage.values()) {
            if (!selected.containsKey(stage)) throw bad("演绎阶段缺失");
            result.add(selected.get(stage));
        }
        return List.copyOf(result);
    }

    private static ObjectNode restoredOutputs(ObjectNode record, List<ComponentBinding> pipeline) {
        ObjectNode outputs = JSON.createObjectNode();
        int next = 0;
        boolean stopped = false;
        for (JsonNode entry : array(record, "stages")) {
            object(entry, Set.of("stage", "componentId", "componentVersion", "status"), Set.of("output", "error"));
            if (stopped || next >= pipeline.size()) throw bad("停止或完成之后不能出现额外阶段");
            ComponentBinding binding = pipeline.get(next);
            if (!binding.stage.key().equals(text(entry, "stage"))) throw bad("演绎阶段顺序不连续或名称不规范");
            if (!binding.id.equals(text(entry, "componentId")) || !binding.version.equals(text(entry, "componentVersion")))
                throw bad("历史阶段组件版本不符");
            String status = text(entry, "status"), stage = text(entry, "stage");
            if (status.equals("completed")) {
                if (entry.has("error")) throw bad("完成阶段不能附带失败记录");
                if (outputs.has(stage)) throw bad("演绎记录含重复完成阶段");
                ObjectNode output = validateResult(binding.stage, entry.get("output"), outputs);
                outputs.set(stage, output); next++;
                stopped = !text(output, "status").equals("ready");
            } else if (!status.equals("failed") || !entry.path("error").isObject() || entry.has("output")) throw bad("演绎阶段状态无效");
        }
        return outputs;
    }

    private static ObjectNode validateResult(ExperienceComponent.Stage stage, JsonNode value, ObjectNode outputs) {
        if (value == null) throw bad("演绎组件未返回结果");
        Set<String> optional = new HashSet<>(Set.of("evidence", "gaps", "notes", "reason"));
        switch (stage) {
            case CANDIDATES -> optional.add("candidates");
            case SELECT -> optional.add("candidateId");
            case RESOLVE -> optional.add("action");
            case FEEDBACK -> optional.addAll(Set.of("after", "receipt", "remainingGaps"));
            default -> { }
        }
        object(value, Set.of("status"), optional); data(value);
        String status = text(value, "status");
        if (!Set.of("ready", "needsInput", "blocked", "satisfied").contains(status)) throw bad("组件结果状态不支持");
        if (status.equals("satisfied") && stage != ExperienceComponent.Stage.PREPARE && stage != ExperienceComponent.Stage.CANDIDATES)
            throw bad("目标满足只能在准备或候选阶段正常停止");
        if (status.equals("satisfied")) text(value, "reason");
        for (String field : List.of("evidence", "gaps", "notes")) if (value.has(field)) array(value, field);
        if (status.equals("ready")) switch (stage) {
            case CANDIDATES -> {
                ArrayNode candidates = array(value, "candidates");
                if (candidates.isEmpty()) throw bad("ready候选不能为空");
                Set<String> seen = new HashSet<>();
                for (JsonNode candidate : candidates) {
                    object(candidate, Set.of("id", "module", "arguments", "description", "priority", "evidence"), Set.of("moduleVersion"));
                    if (!seen.add(id(candidate, "id"))) throw bad("候选标识重复");
                    id(candidate, "module"); moduleVersion(candidate); text(candidate, "description");
                    if (!candidate.path("priority").isIntegralNumber()) throw bad("候选优先级须为整数");
                    arguments(candidate.get("arguments")); array(candidate, "evidence");
                }
            }
            case SELECT -> {
                text(value, "reason"); selected(outputs, text(value, "candidateId"));
            }
            case RESOLVE -> {
                JsonNode action = object(value.get("action"), Set.of("module", "arguments"), Set.of("moduleVersion"));
                arguments(action.get("arguments"));
                JsonNode chosen = selected(outputs, text(outputs.path("select"), "candidateId"));
                if (!text(action, "module").equals(text(chosen, "module"))
                    || !moduleVersion(action).equals(moduleVersion(chosen))
                    || !canonical(action.get("arguments")).equals(canonical(chosen.get("arguments"))))
                    throw bad("行动转换不能替换已经选择的候选");
            }
            case FEEDBACK -> { if (value.has("remainingGaps")) array(value, "remainingGaps"); }
            default -> { }
        }
        return canonicalObject(value);
    }

    private static JsonNode selected(ObjectNode outputs, String id) {
        for (JsonNode candidate : array(outputs.path("candidates"), "candidates"))
            if (text(candidate, "id").equals(id)) return candidate;
        throw bad("选择的候选不存在");
    }
    private static void arguments(JsonNode args) {
        if (args == null || !args.isObject()) throw bad("候选arguments必须是对象");
        args.fieldNames().forEachRemaining(field -> { if (IDENTITY.contains(field)) throw bad("候选不能声明提交身份：" + field); });
        data(args);
    }
    private static void checkInitial(JsonNode view, ObjectNode request) {
        if (!view.path("self").isObject() || !text(view, "actor").equals(text(request, "actor"))) throw bad("演绎只支持指定人物视图");
        if (!text(view, "worldId").equals(text(request, "worldId"))) throw bad("演绎所属世界不符");
        if (integer(view, "revision") != integer(request, "expectedRevision")) throw bad("演绎起始版本已经变化，请建立新尝试");
    }
    private static String key(String id, String version) {
        if (id == null || id.isBlank() || id.startsWith("@") || version == null || version.isBlank()
            || id.indexOf('\n') >= 0 || version.indexOf('\n') >= 0
            || id.indexOf('\r') >= 0 || version.indexOf('\r') >= 0) throw bad("组件标识或版本无效");
        return id + "\n" + version;
    }
    private record ComponentBinding(String id, String version, ExperienceComponent.Stage stage, ExperienceComponent component) {}
    private static void validateComponentIdentity(ComponentBinding binding) {
        if (!binding.id.equals(binding.component.id()) || !binding.version.equals(binding.component.version())
            || binding.stage != binding.component.stage()) throw bad("演绎组件在注册后改变了标识、版本或阶段");
    }
    private static ObjectNode failure(Exception failure) {
        return JSON.createObjectNode().put("type", failure.getClass().getSimpleName())
            .put("message", failure.getMessage() == null ? "演绎失败" : failure.getMessage());
    }
    private static void save(Path file, ObjectNode record) throws IOException {
        Path temporary = Files.createTempFile(file.getParent(), ".experience-", ".tmp");
        try {
            JSON.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), record);
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }
}
