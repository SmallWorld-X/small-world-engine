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

/** 有限的一步步目标推进。独立目录保存过程，不替代正式世界的提交与恢复合同。 */
public final class GoalJourney {
    private static final int LIMIT = 32;
    private static final Set<String> TERMINAL = Set.of("satisfied", "needsInput", "blocked");
    private static final Set<String> ATTEMPT_STATUSES = Set.of("running", "failed", "completed", "satisfied", "needsInput", "blocked");
    private static final Map<String, ReentrantLock> LOCKS = new ConcurrentHashMap<>();

    private GoalJourney() {}

    public static ObjectNode run(WorldStore world, JsonNode originalRequest, Path directory, int maxAttempts) throws IOException {
        return run(world, new ExperienceEngine(), originalRequest, directory, maxAttempts);
    }

    public static ObjectNode run(WorldStore world, ExperienceEngine engine, JsonNode originalRequest, Path directory, int maxAttempts) throws IOException {
        Objects.requireNonNull(world, "正式世界不能为空");
        Objects.requireNonNull(engine, "演绎引擎不能为空");
        if (maxAttempts < 1 || maxAttempts > LIMIT) throw bad("目标推进次数须在1到32之间");
        ObjectNode request = canonicalObject(object(originalRequest, "schemaVersion", "worldId", "requestId",
            "expectedRevision", "actor", "intent", "scenario", "components"));
        version(request); id(request, "worldId"); id(request, "requestId"); id(request, "actor");
        integer(request, "expectedRevision"); text(request, "intent"); array(request, "components");
        object(request.get("scenario"), Set.of("goal"), Set.of("remainingGaps")); data(request);

        Path folder = Objects.requireNonNull(directory, "推进目录不能为空").toAbsolutePath().normalize();
        if (Files.isSymbolicLink(folder)) throw bad("推进目录不能是符号链接");
        Files.createDirectories(folder); folder = folder.toRealPath();
        protectFiles(folder, world);
        ReentrantLock local = LOCKS.computeIfAbsent(folder.toString().toLowerCase(Locale.ROOT), ignored -> new ReentrantLock());
        local.lock();
        try { return lockedRun(world, engine, request, folder, maxAttempts); }
        finally { local.unlock(); }
    }

    @SuppressWarnings("try")
    private static ObjectNode lockedRun(WorldStore world, ExperienceEngine engine, ObjectNode request, Path folder, int maxAttempts) throws IOException {
        protectFiles(folder, world);
        Path manifestFile = folder.resolve("journey.json");
        try (FileChannel channel = FileChannel.open(folder.resolve("manifest.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignored = channel.lock()) {
            int existing = existingSteps(folder);
            ObjectNode manifest;
            if (Files.exists(manifestFile, LinkOption.NOFOLLOW_LINKS)) {
                manifest = object(JSON.readTree(manifestFile.toFile()),
                    Set.of("schemaVersion", "kind", "worldFile", "originalRequest", "status", "attempts"), Set.of("error"));
                version(manifest);
                if (!text(manifest, "kind").equals("goal-journey")
                    || !text(manifest, "worldFile").equals(world.path().toString())
                    || !canonical(manifest.get("originalRequest")).equals(request))
                    throw bad("目标推进目录属于另一世界、请求或组件版本");
                if (!Set.of("running", "paused", "failed", "satisfied", "needsInput", "blocked").contains(text(manifest, "status")))
                    throw bad("目标推进记录状态无效");
                checkSummaries(array(manifest, "attempts"), request, existing);
            } else {
                if (existing != 0) throw bad("没有目录清单，不能冒领已经存在的演绎步骤");
                manifest = JSON.createObjectNode().put("schemaVersion", 1).put("kind", "goal-journey")
                    .put("worldFile", world.path().toString()).put("status", "running");
                manifest.set("originalRequest", request.deepCopy()); manifest.putArray("attempts");
                save(manifestFile, manifest, world);
            }

            ArrayNode attempts = array(manifest, "attempts");
            int recorded = Math.max(existing, attempts.size());
            int bound = Math.max(maxAttempts, recorded);
            int revision = integer(request, "expectedRevision");
            for (int index = 1; index <= bound; index++) {
                Path stepFile = folder.resolve(stepName(index));
                ObjectNode stepRequest = canonicalObject(request.deepCopy().put("requestId", stepId(request, index)).put("expectedRevision", revision));
                ObjectNode summary = summary(stepRequest, index).put("status", "running");
                try {
                    protect(stepFile, world); protect(stepFile.resolveSibling(stepFile.getFileName() + ".lock"), world);
                    if (Files.exists(stepFile, LinkOption.NOFOLLOW_LINKS)) {
                        JsonNode saved = JSON.readTree(stepFile.toFile());
                        if (!saved.isObject() || !canonical(saved.path("request")).equals(stepRequest))
                            throw bad("步骤请求与原目标或上一真实回执不一致：" + index);
                        stepRequest = canonicalObject(saved.get("request"));
                        String savedStatus = text(saved, "status");
                        if (index > maxAttempts && !savedStatus.equals("completed") && !TERMINAL.contains(savedStatus))
                            return paused(manifestFile, manifest, world);
                    } else if (index > maxAttempts) return paused(manifestFile, manifest, world);
                    putSummary(attempts, index, summary);
                    manifest.put("status", "running"); manifest.remove("error"); save(manifestFile, manifest, world);
                    ObjectNode record = engine.run(world, stepRequest, stepFile);
                    String status = text(record, "status");
                    if (!status.equals("completed") && !TERMINAL.contains(status)) throw bad("步骤尚未产生可用结果");
                    summary.put("status", status);
                    if (record.has("receipt")) summary.set("receipt", record.get("receipt").deepCopy());
                    if (TERMINAL.contains(status)) {
                        if (index < recorded) throw bad("目标已经停止，目录却含后续步骤");
                        manifest.put("status", status); save(manifestFile, manifest, world); return canonicalObject(manifest);
                    }
                    JsonNode receipt = object(record.get("receipt"), "schemaVersion", "worldId", "requestId", "revision", "status");
                    version(receipt);
                    if (!text(receipt, "worldId").equals(text(request, "worldId"))
                        || !text(receipt, "requestId").equals(text(stepRequest, "requestId"))
                        || !text(receipt, "status").equals("committed") || integer(receipt, "revision") <= revision)
                        throw bad("步骤没有对应的正式提交回执");
                    revision = integer(receipt, "revision");
                    save(manifestFile, manifest, world);
                } catch (RuntimeException | IOException failure) {
                    summary.put("status", "failed");
                    putSummary(attempts, index, summary);
                    manifest.put("status", "failed").set("error", error(failure));
                    try { save(manifestFile, manifest, world); }
                    catch (IOException | RuntimeException saving) { failure.addSuppressed(saving); }
                    throw failure;
                }
            }
            return paused(manifestFile, manifest, world);
        }
    }

    private static ObjectNode summary(ObjectNode request, int index) {
        return JSON.createObjectNode().put("index", index).put("requestId", text(request, "requestId"))
            .put("expectedRevision", integer(request, "expectedRevision")).put("recordFile", stepName(index));
    }

    private static void putSummary(ArrayNode attempts, int index, ObjectNode summary) {
        if (index == attempts.size() + 1) attempts.add(summary);
        else if (index <= attempts.size()) attempts.set(index - 1, summary);
        else throw bad("步骤摘要不连续");
    }

    private static void checkSummaries(ArrayNode attempts, ObjectNode request, int existing) {
        if (attempts.size() > LIMIT || attempts.size() > existing + 1) throw bad("步骤摘要与记录文件数量不一致");
        boolean stopped = false;
        for (int offset = 0; offset < attempts.size(); offset++) {
            JsonNode row = attempts.get(offset);
            object(row, Set.of("index", "requestId", "expectedRevision", "recordFile", "status"), Set.of("receipt"));
            int index = offset + 1; String status = text(row, "status");
            if (stopped || integer(row, "index") != index || !text(row, "requestId").equals(stepId(request, index))
                || !text(row, "recordFile").equals(stepName(index)) || !ATTEMPT_STATUSES.contains(status))
                throw bad("步骤摘要身份或顺序无效");
            integer(row, "expectedRevision");
            if (index > existing && !Set.of("running", "failed").contains(status)) throw bad("已完成步骤的记录文件缺失");
            if (TERMINAL.contains(status)) stopped = true;
            if (row.has("receipt")) object(row.get("receipt"), "schemaVersion", "worldId", "requestId", "revision", "status");
        }
    }

    private static int existingSteps(Path folder) throws IOException {
        int count = 0;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(folder, "step-*.json")) {
            for (Path file : files) {
                String name = file.getFileName().toString();
                if (!name.matches("step-[0-9]{3}\\.json")) throw bad("步骤文件名不规范");
                int index = Integer.parseInt(name.substring(5, 8));
                if (index < 1 || index > LIMIT) throw bad("步骤文件超过支持范围");
                count++;
            }
        }
        for (int index = 1; index <= count; index++) if (!Files.exists(folder.resolve(stepName(index)), LinkOption.NOFOLLOW_LINKS))
            throw bad("步骤文件不连续");
        return count;
    }

    private static ObjectNode paused(Path file, ObjectNode manifest, WorldStore world) throws IOException {
        manifest.put("status", "paused"); save(file, manifest, world); return canonicalObject(manifest);
    }

    private static String stepName(int index) { return String.format(Locale.ROOT, "step-%03d.json", index); }
    private static String stepId(ObjectNode request, int index) { return text(request, "requestId") + ".step-" + index; }

    private static void protectFiles(Path folder, WorldStore world) throws IOException {
        protect(folder.resolve("journey.json"), world); protect(folder.resolve("manifest.lock"), world);
        for (int index = 1; index <= LIMIT; index++) {
            Path step = folder.resolve(stepName(index)); protect(step, world);
            protect(step.resolveSibling(step.getFileName() + ".lock"), world);
        }
    }

    private static void protect(Path path, WorldStore world) throws IOException {
        Path worldFile = world.path(), worldLock = worldFile.resolveSibling(worldFile.getFileName() + ".lock");
        if (Files.isSymbolicLink(path)) throw bad("推进记录或锁文件不能是符号链接");
        for (Path authority : List.of(worldFile, worldLock)) {
            if (path.toString().equalsIgnoreCase(authority.toString())
                || (Files.exists(path, LinkOption.NOFOLLOW_LINKS) && Files.exists(authority, LinkOption.NOFOLLOW_LINKS)
                    && Files.isSameFile(path, authority))) throw bad("推进记录或锁文件不能占用正式世界或其锁");
        }
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
            throw bad("推进记录或锁文件不是普通文件");
    }

    private static ObjectNode error(Exception failure) {
        return JSON.createObjectNode().put("type", failure.getClass().getSimpleName())
            .put("message", failure.getMessage() == null ? "目标推进失败" : failure.getMessage());
    }

    private static void save(Path file, ObjectNode manifest, WorldStore world) throws IOException {
        protect(file, world);
        Path temporary = Files.createTempFile(file.getParent(), ".journey-", ".tmp");
        try {
            JSON.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), manifest);
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }
}
