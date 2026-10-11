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

/** 有界的多人物衔接。配置与阶段记录在旁账，事实与获知仍只来自正式世界。 */
public final class WorldContinuation {
    private static final int LIMIT = 32;
    private static final Map<String, ReentrantLock> LOCKS = new ConcurrentHashMap<>();
    private WorldContinuation() {}

    public static ObjectNode run(WorldStore world, ExperienceEngine engine, JsonNode incoming, Path directory, int budget) throws IOException {
        Objects.requireNonNull(world, "正式世界不能为空");
        Objects.requireNonNull(engine, "演绎引擎不能为空");
        if (budget < 1 || budget > LIMIT) throw bad("连续演绎尝试上限须在1到32之间");
        ObjectNode request = validate(incoming);
        Path folder = Objects.requireNonNull(directory, "连续演绎目录不能为空").toAbsolutePath().normalize();
        if (Files.isSymbolicLink(folder)) throw bad("连续演绎目录不能是符号链接");
        Files.createDirectories(folder); folder = folder.toRealPath();
        protectFiles(folder, world);
        ReentrantLock local = LOCKS.computeIfAbsent(folder.toString().toLowerCase(Locale.ROOT), ignored -> new ReentrantLock());
        local.lock();
        try { return lockedRun(world, engine, request, folder, budget); }
        finally { local.unlock(); }
    }

    private static ObjectNode validate(JsonNode incoming) {
        ObjectNode request = canonicalObject(object(incoming, "schemaVersion", "worldId", "requestId", "expectedRevision", "activities", "starts", "onKnowledge"));
        version(request); id(request, "worldId"); id(request, "requestId"); integer(request, "expectedRevision"); data(request);
        Map<String, JsonNode> activities = new LinkedHashMap<>();
        ArrayNode rows = array(request, "activities");
        if (rows.isEmpty() || rows.size() > 16) throw bad("一次连续演绎须声明1到16项活动");
        for (JsonNode activity : rows) {
            object(activity, "id", "actor", "kind", "intent", "scenario", "components");
            String name = id(activity, "id"); id(activity, "actor"); text(activity, "intent");
            if (activities.putIfAbsent(name, activity) != null) throw bad("连续演绎活动标识重复");
            String kind = text(activity, "kind");
            if (kind.equals("goal")) object(activity.get("scenario"), Set.of("goal"), Set.of("remainingGaps"));
            else if (kind.equals("message")) object(activity.get("scenario"), Set.of(), Set.of("remainingGaps"));
            else throw bad("连续演绎活动须为goal或message");
            ArrayNode components = array(activity, "components");
            if (components.size() != ExperienceComponent.Stage.values().length) throw bad("活动须配置五个演绎阶段");
            Set<String> stages = new HashSet<>();
            for (JsonNode component : components) {
                object(component, "stage", "id", "version"); id(component, "id"); moduleVersion(text(component, "version"));
                String stage = text(component, "stage");
                if (!Set.of("prepare", "candidates", "select", "resolve", "feedback").contains(stage) || !stages.add(stage))
                    throw bad("活动阶段未知或重复");
            }
        }
        Set<String> starts = new HashSet<>();
        if (array(request, "starts").isEmpty()) throw bad("连续演绎须有起始目标");
        for (JsonNode start : array(request, "starts")) {
            String name = id(start, "starts"); JsonNode activity = activities.get(name);
            if (activity == null || !text(activity, "kind").equals("goal") || !starts.add(name))
                throw bad("起始目标缺失、重复或需要尚未发生的消息");
        }
        Set<String> watches = new HashSet<>();
        if (array(request, "onKnowledge").size() > 64) throw bad("连续演绎关注项超过64项");
        for (JsonNode watch : array(request, "onKnowledge")) {
            object(watch, "activityId", "factId");
            String name = id(watch, "activityId"); id(watch, "factId");
            if (!activities.containsKey(name) || !watches.add(canonical(watch).toString())) throw bad("关注项重复或引用未知活动");
        }
        return request;
    }

    @SuppressWarnings("try")
    private static ObjectNode lockedRun(WorldStore world, ExperienceEngine engine, ObjectNode request, Path folder, int budget) throws IOException {
        protectFiles(folder, world);
        try (FileChannel channel = FileChannel.open(folder.resolve("continuation.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignored = channel.lock()) {
            int existing = existingSteps(folder);
            ObjectNode identity = JSON.createObjectNode().put("schemaVersion", 1).put("kind", "world-continuation")
                .put("worldFile", world.path().toString());
            identity.set("request", request.deepCopy());
            Path manifest = folder.resolve("continuation.json");
            if (Files.exists(manifest, LinkOption.NOFOLLOW_LINKS)) {
                if (!canonical(JSON.readTree(manifest.toFile())).equals(canonical(identity)))
                    throw bad("连续演绎目录属于另一世界、活动、输入或组件版本");
            } else {
                if (existing != 0) throw bad("缺少连续演绎配置，不能冒领已有阶段记录");
                // 在保存配置前核对每个起始人物；作者或不存在的身份不会成为活动执行者。
                for (JsonNode activity : array(request, "activities")) {
                    JsonNode view = world.viewAt(text(activity, "actor"), integer(request, "expectedRevision"));
                    if (!view.path("actor").asText().equals(text(activity, "actor")) || !view.has("self")) throw bad("活动须由普通人物执行");
                }
                if (!text(world.inspect(), "worldId").equals(text(request, "worldId"))
                    || integer(world.inspect(), "revision") != integer(request, "expectedRevision")) throw bad("连续演绎起始世界或版本不一致");
                save(manifest, identity);
            }
            Map<String, JsonNode> activities = new LinkedHashMap<>();
            for (JsonNode activity : array(request, "activities")) activities.put(text(activity, "id"), activity);
            ArrayDeque<ObjectNode> agenda = new ArrayDeque<>();
            for (JsonNode start : array(request, "starts")) agenda.add(task(start.asText(), null));
            ObjectNode result = JSON.createObjectNode().put("schemaVersion", 1).put("worldId", text(request, "worldId"))
                .put("requestId", text(request, "requestId"));
            ArrayNode attempts = result.putArray("attempts"), notifications = result.putArray("notifications");
            ObjectNode outcomes = result.putObject("outcomes");
            int revision = integer(request, "expectedRevision");
            boolean deferred = false;
            while (!agenda.isEmpty()) {
                int index = attempts.size() + 1;
                if (index > Math.max(budget, existing)) break;
                ObjectNode current = agenda.remove();
                JsonNode activity = activities.get(text(current, "activityId"));
                ObjectNode turn = turnRequest(request, activity, current, index, revision);
                Path file = folder.resolve(stepName(index));
                protect(file, world); protect(file.resolveSibling(file.getFileName() + ".lock"), world);
                if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                    JsonNode saved = JSON.readTree(file.toFile());
                    if (!canonical(saved.path("request")).equals(turn)) throw bad("连续演绎阶段与真实衔接顺序不一致：" + index);
                    String status = text(saved, "status");
                    if (index > budget && !Set.of("completed", "needsInput", "blocked", "satisfied").contains(status)) {
                        if (index < existing) throw bad("未完成阶段后不能含有额外记录");
                        deferred = true; agenda.addFirst(current); break;
                    }
                } else if (index > budget) { agenda.addFirst(current); break; }
                // 演绎引擎复核每份已完成记录的回执与历史投影；失败阶段由原实现继续。
                ObjectNode record = engine.run(world, turn, file);
                String status = text(record, "status");
                if (!Set.of("completed", "needsInput", "blocked", "satisfied").contains(status)) throw bad("活动未形成可用结果");
                ObjectNode summary = attempts.addObject().put("index", index).put("activityId", text(activity, "id"))
                    .put("actor", text(activity, "actor")).put("expectedRevision", revision).put("status", status)
                    .put("recordFile", stepName(index));
                if (current.has("message")) summary.set("trigger", current.get("message").deepCopy());
                ArrayNode gaps = summary.putArray("gaps");
                summary.set("remainingGaps", activity.path("scenario").has("remainingGaps")
                    ? activity.get("scenario").get("remainingGaps").deepCopy() : JSON.createArrayNode());
                for (JsonNode stage : record.path("stages")) {
                    if (!stage.path("status").asText().equals("completed")) continue;
                    JsonNode output = stage.path("output");
                    if (output.has("remainingGaps")) summary.set("remainingGaps", output.get("remainingGaps").deepCopy());
                    if (!output.path("status").asText().equals("ready") && output.has("gaps"))
                        output.get("gaps").forEach(gap -> gaps.add(gap.deepCopy()));
                    if (!output.path("status").asText().equals("ready") && output.has("reason")) summary.put("reason", output.get("reason").asText());
                }
                if (status.equals("completed")) {
                    JsonNode receipt = record.get("receipt");
                    if (integer(receipt, "revision") != Math.addExact(revision, 1)) throw bad("活动回执未紧接上一世界版本");
                    revision = integer(receipt, "revision"); summary.set("receipt", receipt.deepCopy());
                    dispatch(world, request, activities, agenda, revision, notifications);
                    if (text(activity, "kind").equals("goal")) enqueue(agenda, task(text(activity, "id"), null), true);
                }
                outcomes.set(text(activity, "id"), summary.deepCopy());
            }
            if (attempts.size() < existing && !deferred) throw bad("活动已经停止，目录却包含额外的阶段记录");
            result.put("revision", revision);
            result.put("status", agenda.isEmpty() ? "quiescent" : attempts.size() == LIMIT ? "limitReached" : "paused");
            ArrayNode pending = result.putArray("pending"); agenda.forEach(item -> pending.add(item.deepCopy()));
            // 安静仅表示没有待运行活动；各活动的缺口或未完成目标仍分别保留。
            return canonicalObject(result);
        }
    }

    private static ObjectNode turnRequest(ObjectNode request, JsonNode activity, ObjectNode task, int index, int revision) {
        ObjectNode result = JSON.createObjectNode().put("schemaVersion", 1).put("worldId", text(request, "worldId"))
            .put("requestId", text(request, "requestId") + ".turn-" + index).put("expectedRevision", revision)
            .put("actor", text(activity, "actor")).put("intent", text(activity, "intent"));
        ObjectNode scenario = (ObjectNode) activity.get("scenario").deepCopy();
        if (text(activity, "kind").equals("message")) {
            if (!task.has("message")) throw bad("消息回应缺少实际获知触发");
            scenario.set("message", task.get("message").deepCopy());
        }
        result.set("scenario", scenario); result.set("components", activity.get("components").deepCopy());
        return canonicalObject(result);
    }

    private static void dispatch(WorldStore world, ObjectNode request, Map<String, JsonNode> activities,
                                 ArrayDeque<ObjectNode> agenda, int revision, ArrayNode notifications) throws IOException {
        JsonNode history = world.history();
        JsonNode event = history.path("events").get(revision - 1);
        if (event == null || integer(event, "revision") != revision) throw bad("活动回执缺少对应正式事件");
        Set<String> deliveries = new LinkedHashSet<>();
        ArrayDeque<ObjectNode> woken = new ArrayDeque<>();
        for (JsonNode effect : event.path("proposal").path("effects")) {
            String type = text(effect, "type"), who, fact;
            if (type.equals("knowledge.share")) { who = text(effect, "to"); fact = text(effect, "factId"); }
            else if (type.equals("knowledge.learn")) { who = text(effect, "personId"); fact = text(effect, "factId"); }
            else continue; // 改变真相并不自动让不知情的人获得消息。
            ObjectNode key = JSON.createObjectNode().put("actor", who).put("factId", fact);
            if (!deliveries.add(key.toString())) continue;
            JsonNode view = world.viewAt(who, revision), known = null;
            for (JsonNode item : view.path("knowledge")) if (text(item, "factId").equals(fact)) known = item;
            if (known == null || integer(known, "revision") != revision) throw bad("获知效果没有对应人物知识记录");
            ObjectNode message = JSON.createObjectNode().put("factId", fact).put("sourcePersonId", text(known, "source"))
                .put("receivedRevision", revision);
            boolean handled = false;
            for (JsonNode watch : array(request, "onKnowledge")) {
                JsonNode activity = activities.get(text(watch, "activityId"));
                if (!text(activity, "actor").equals(who) || !text(watch, "factId").equals(fact)) continue;
                if (text(activity, "kind").equals("message") && text(known, "source").startsWith("@")) continue;
                enqueue(woken, task(text(activity, "id"), message), text(activity, "kind").equals("goal"));
                handled = true;
            }
            ObjectNode notice = notifications.addObject().put("actor", who).put("scheduled", handled);
            notice.set("message", message);
        }
        // 当前后果引出的反应先处理，再继续原目标；同一批获知保持效果与关注项的声明顺序。
        while (!woken.isEmpty()) {
            ObjectNode task = woken.removeLast();
            if (text(activities.get(text(task, "activityId")), "kind").equals("goal"))
                agenda.removeIf(queued -> text(queued, "activityId").equals(text(task, "activityId")));
            agenda.addFirst(task);
        }
    }

    private static ObjectNode task(String activity, JsonNode message) {
        ObjectNode task = JSON.createObjectNode().put("activityId", activity);
        if (message != null) task.set("message", message.deepCopy());
        return task;
    }
    private static void enqueue(ArrayDeque<ObjectNode> agenda, ObjectNode task, boolean goal) {
        for (ObjectNode queued : agenda) {
            if (goal && text(queued, "activityId").equals(text(task, "activityId"))) return;
            if (!goal && canonical(queued).equals(canonical(task))) return;
        }
        agenda.add(task);
    }
    private static String stepName(int index) { return String.format(Locale.ROOT, "turn-%03d.json", index); }
    private static int existingSteps(Path folder) throws IOException {
        int count = 0;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(folder, "turn-*.json")) {
            for (Path file : files) {
                String name = file.getFileName().toString();
                if (!name.matches("turn-[0-9]{3}\\.json") || Integer.parseInt(name.substring(5, 8)) < 1
                    || Integer.parseInt(name.substring(5, 8)) > LIMIT) throw bad("连续演绎阶段文件名或数量无效");
                count++;
            }
        }
        for (int i = 1; i <= count; i++) if (!Files.exists(folder.resolve(stepName(i)), LinkOption.NOFOLLOW_LINKS))
            throw bad("连续演绎阶段文件不连续");
        return count;
    }
    private static void protectFiles(Path folder, WorldStore world) throws IOException {
        protect(folder.resolve("continuation.json"), world); protect(folder.resolve("continuation.lock"), world);
        for (int i = 1; i <= LIMIT; i++) {
            Path file = folder.resolve(stepName(i)); protect(file, world); protect(file.resolveSibling(file.getFileName() + ".lock"), world);
        }
    }
    private static void protect(Path path, WorldStore world) throws IOException {
        if (Files.isSymbolicLink(path)) throw bad("连续演绎记录或锁不能是符号链接");
        for (Path authority : List.of(world.path(), world.path().resolveSibling(world.path().getFileName() + ".lock"))) {
            if (path.toString().equalsIgnoreCase(authority.toString()) || (Files.exists(path) && Files.exists(authority) && Files.isSameFile(path, authority)))
                throw bad("连续演绎记录或锁不能占用正式世界");
        }
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw bad("连续演绎记录或锁须为普通文件");
    }
    private static void save(Path path, JsonNode value) throws IOException {
        Path temp = Files.createTempFile(path.getParent(), ".continuation-", ".tmp");
        try {
            JSON.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), value);
            Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE);
        } finally { Files.deleteIfExists(temp); }
    }
}
