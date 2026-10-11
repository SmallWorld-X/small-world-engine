package creativeworld;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static creativeworld.Protocol.*;

/** 跨场景结果与恢复反例；不会把队列耗尽当作所有业务目标完成。 */
public final class ContinuationCheck {
    private static final ArrayNode RESULTS = JSON.createArrayNode();
    private static Path examples;
    private record Fixture(Path root, WorldStore world, ObjectNode request, ObjectNode seed) {}
    private ContinuationCheck() {}

    public static void main(String[] args) throws Exception {
        examples = Path.of(args[1]).resolve("continuation");
        run("河港镇：目标引出申请、保管员登记、学徒到场核实", () -> {
            Fixture f = fixture("river"); JsonNode report = run(f, 32); positive(f, report, "apprentice", "keeper", "smith", "handoff-state", "registered", 40);
            equal(report.path("attempts").size(), 6, "五次提交与一次满足检查");
            equal(report.path("attempts").get(2).path("actor").asText(), "keeper", "当前获知先唤起保管员回应");
        });
        run("社区工坊：同一机制处理不同状态与人物", () -> {
            Fixture f = fixture("workshop"); JsonNode report = run(f, 32); positive(f, report, "resident", "steward", "volunteer", "equipment-available", true, 4);
        });
        run("暂停、重开恢复与再次执行不增加事件", () -> {
            Fixture f = fixture("river"); JsonNode paused = run(f, 1); equal(paused.path("status").asText(), "paused", "预算用尽明确暂停");
            equal(f.world.inspect().path("revision").asInt(), 1, "只提交第一步");
            JsonNode done = WorldContinuation.run(new WorldStore(f.world.path()), new ExperienceEngine(), f.request, f.root.resolve("episode"), 32);
            byte[] before = Files.readAllBytes(f.world.path());
            equal(run(f, 1), done, "重读完整结果不受较小预算影响"); unchanged(f.world.path(), before);
        });
        run("缺少人物策略保留停止原因，不编造回应成功", () -> {
            Fixture f = fixture("river"); ((ObjectNode) f.seed.path("characters").get(1).path("privateProfile")).remove("responsePolicy"); f = rebuilt(f);
            JsonNode report = run(f, 32); equal(report.path("status").asText(), "quiescent", "没有可运行活动");
            equal(report.path("outcomes").path("process").path("status").asText(), "needsInput", "办理仍缺依据");
            equal(fact(f.world, "handoff-state"), JSON.getNodeFactory().textNode("pending"), "未办理保持待登记");
            check(!report.path("outcomes").path("process").path("gaps").isEmpty(), "保留本人策略缺口");
        });
        run("作者知道旧值，人物不知道时不能代用作者事实", () -> {
            Fixture f = fixture("river"); ((ObjectNode) seedFact(f.seed, "handoff-state")).putArray("knownBy"); f = rebuilt(f);
            JsonNode report = run(f, 32); equal(report.path("outcomes").path("process").path("status").asText(), "needsInput", "本人条件未知");
            equal(fact(f.world, "handoff-state").asText(), "pending", "作者知道不代替人物知道");
        });
        run("取消关注不会把状态变化广播给所有人物", () -> {
            Fixture f = fixture("river"); f.request.putArray("onKnowledge"); JsonNode report = run(f, 32);
            equal(f.world.inspect().path("revision").asInt(), 3, "仅询问移动观察");
            equal(fact(f.world, "handoff-state").asText(), "pending", "没有额外回应");
            check(!report.path("notifications").get(0).path("scheduled").asBoolean(), "未处理通知被保留");
        });
        run("旧消息时间经过再次转述仍保留原证据年", () -> {
            Fixture f = fixture("river"); ((ObjectNode) f.seed.get("worldTime")).put("year", 90);
            f = rebuilt(f);
            ObjectNode advance = JSON.createObjectNode().put("schemaVersion", 1).put("worldId", text(f.request, "worldId"))
                .put("requestId", "advance-before-episode").put("expectedRevision", 0).put("actor", "@author").put("reason", "共同时间前进，旧地点消息没有重新核实");
            advance.putArray("effects").addObject().put("type", "time.advance").put("expectedYear", 90).put("toYear", 100);
            f.world.apply(advance); f.request.put("expectedRevision", 1); run(f, 32);
            JsonNode first = JSON.readTree(f.root.resolve("episode/turn-001.json").toFile());
            JsonNode known = known(first.path("finalView"), "cargo-location");
            equal(known.path("asOfYear").asInt(), 90, "转述保存旧证据时间"); equal(known.path("learnedAtYear").asInt(), 100, "接收在现在");
        });
        run("非法办理整笔拒绝，之前合法提交保留且可恢复", () -> {
            Fixture f = fixture("river"); ObjectNode rule = (ObjectNode) f.seed.path("characters").get(1).path("privateProfile").path("responsePolicy").path("rules").get(0);
            ((ArrayNode) rule.get("stateChanges")).addObject().put("factId", "further-permission").put("expectedValue", false).put("value", true);
            ((ObjectNode) seedFact(f.seed, "further-permission")).putArray("knownBy").add("keeper"); f = rebuilt(f);
            Fixture current = f; rejects(() -> run(current, 32)); equal(f.world.inspect().path("revision").asInt(), 2, "申请前两步有效");
            equal(fact(f.world, "handoff-state").asText(), "pending", "登记没有半提交"); equal(fact(f.world, "further-permission").asBoolean(), false, "不可批准许可");
            byte[] before = Files.readAllBytes(f.world.path()); rejects(() -> run(current, 32)); unchanged(f.world.path(), before);
        });
        run("提交后反馈失败，重开继续原回执且后续只发生一次", () -> {
            Fixture f = fixture("river"); AtomicBoolean fail = new AtomicBoolean(true);
            List<ExperienceComponent> defaults = MessageReactionComponents.stateResponses(); ExperienceComponent feedback = defaults.get(4);
            ExperienceEngine engine = new ExperienceEngine();
            engine.register(new ExperienceComponent() {
                public String id() { return "failure-feedback"; } public String version() { return "1"; }
                public Stage stage() { return Stage.FEEDBACK; }
                public ObjectNode run(Context context) { if (fail.getAndSet(false)) throw bad("模拟提交后反馈中断"); return feedback.run(context); }
            });
            ((ObjectNode) f.request.path("activities").get(1).path("components").get(4)).put("id", "failure-feedback");
            rejects(() -> WorldContinuation.run(f.world, engine, f.request, f.root.resolve("episode"), 32));
            equal(f.world.inspect().path("revision").asInt(), 2, "反馈失败前申请已真实提交");
            JsonNode deferred = WorldContinuation.run(f.world, engine, f.request, f.root.resolve("episode"), 1);
            equal(deferred.path("status").asText(), "paused", "预算不足不会运行提交后未完成阶段");
            equal(f.world.inspect().path("revision").asInt(), 2, "暂停不再次提交");
            JsonNode done = WorldContinuation.run(new WorldStore(f.world.path()), engine, f.request, f.root.resolve("episode"), 32);
            equal(done.path("status").asText(), "quiescent", "原阶段恢复后继续"); equal(done.path("revision").asInt(), 5, "不重复申请");
        });
        run("核实结果不抹去作者声明的其他未决事项", () -> {
            Fixture f = fixture("river");
            ((ObjectNode) f.request.path("activities").get(0).get("scenario")).putArray("remainingGaps").add("取货许可和实际运送尚未完成");
            JsonNode report = run(f, 32);
            equal(report.path("outcomes").path("inspect").path("status").asText(), "satisfied", "仅现场核实满足");
            equal(report.path("outcomes").path("inspect").path("remainingGaps").get(0).asText(), "取货许可和实际运送尚未完成", "其他事项保留");
        });
        run("阶段版本缺失明确拒绝，不能临时偷换实现", () -> {
            Fixture f = fixture("river"); run(f, 1);
            ((ObjectNode) f.request.path("activities").get(1).path("components").get(0)).put("version", "missing");
            byte[] before = Files.readAllBytes(f.world.path()); rejects(() -> run(f, 32)); unchanged(f.world.path(), before);
        });
        run("篡改完成回执不会制造新的正式提交", () -> {
            Fixture f = fixture("river"); run(f, 32); Path file = f.root.resolve("episode/turn-003.json");
            ObjectNode record = (ObjectNode) JSON.readTree(file.toFile()); ((ObjectNode) record.get("receipt")).put("revision", 99); write(file, record);
            byte[] before = Files.readAllBytes(f.world.path()); rejects(() -> run(f, 32)); unchanged(f.world.path(), before);
        });
        run("衔接目录不能冒领另一份活动配置", () -> {
            Fixture f = fixture("river"); run(f, 1); ObjectNode changed = f.request.deepCopy(); changed.putArray("onKnowledge");
            byte[] before = Files.readAllBytes(f.world.path()); rejects(() -> WorldContinuation.run(f.world, new ExperienceEngine(), changed, f.root.resolve("episode"), 32)); unchanged(f.world.path(), before);
        });
        run("真实世界被外部推进后不自动重排未完成尝试", () -> {
            Fixture f = fixture("river"); run(f, 1);
            ObjectNode proposal = JSON.createObjectNode().put("schemaVersion", 1).put("worldId", text(f.request, "worldId"))
                .put("requestId", "external-change").put("expectedRevision", 1).put("actor", "@author").put("reason", "外部前进一年");
            proposal.putArray("effects").addObject().put("type", "time.advance").put("expectedYear", 100).put("toYear", 101);
            f.world.apply(proposal); byte[] before = Files.readAllBytes(f.world.path()); rejects(() -> run(f, 32)); unchanged(f.world.path(), before);
        });
        run("消息互相转述循环到达硬上限且不会无止境提交", () -> {
            Fixture f = fixture("river");
            for (int i = 0; i < 2; i++) {
                ObjectNode profile = (ObjectNode) f.seed.path("characters").get(i).path("privateProfile");
                ObjectNode policy = profile.putObject("responsePolicy").put("goalId", "repeat").put("goal", "转述同一地点");
                ObjectNode rule = policy.putArray("rules").addObject().put("id", "repeat").put("priority", 1).put("description", "仅用于有界循环反例");
                rule.putObject("when").put("factId", "cargo-location").put("equals", "warehouse"); rule.putArray("conditions"); rule.putArray("shareFactIds").add("cargo-location");
                ObjectNode activity = (ObjectNode) f.request.path("activities").get(i + 1);
                for (JsonNode row : activity.path("components")) ((ObjectNode) row).put("id", row.path("id").asText().replace("state-reaction", "message-reaction"));
            }
            f.request.putArray("onKnowledge").addObject().put("activityId", "request").put("factId", "cargo-location");
            ((ArrayNode) f.request.get("onKnowledge")).addObject().put("activityId", "process").put("factId", "cargo-location");
            f = rebuilt(f); JsonNode report = run(f, 32); equal(report.path("status").asText(), "limitReached", "循环明确停止");
            equal(report.path("attempts").size(), 32, "最多32次尝试"); byte[] before = Files.readAllBytes(f.world.path()); equal(run(f, 32), report, "重开维持同一上限结果"); unchanged(f.world.path(), before);
        });
        run("并发执行同一连续经历只提交同一组事件", () -> {
            Fixture f = fixture("workshop"); ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Future<JsonNode> first = pool.submit(() -> run(f, 32)), second = pool.submit(() -> run(f, 32));
                equal(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS), "两个调用得到一致经历"); equal(f.world.inspect().path("revision").asInt(), 5, "只有五笔事件");
            } finally { pool.shutdownNow(); }
        });
        run("两个世界的状态回应拒绝自定义选择较低优先级规则", () -> rejectedStateChoice(false));
        run("两个世界的状态回应拒绝自定义选择同分后项", () -> rejectedStateChoice(true));
        run("两个世界接受自定义选择最高优先级规则，恢复不重复提交", () -> acceptedStateChoice(false));
        run("两个世界接受自定义选择同分首项，恢复不重复提交", () -> acceptedStateChoice(true));
        run("阶段顺序缺失、目录孤儿记录被拒绝", () -> {
            Fixture f = fixture("river"); run(f, 1); Files.move(f.root.resolve("episode/turn-001.json"), f.root.resolve("episode/turn-002.json"));
            byte[] before = Files.readAllBytes(f.world.path()); rejects(() -> run(f, 32)); unchanged(f.world.path(), before);
        });
        run("过程文件不得占用世界文件或锁", () -> {
            Path root = Files.createTempDirectory("continuation-alias-");
            WorldStore world = new WorldStore(root.resolve("continuation.json"));
            ObjectNode seed = read("river-seed.json"), request = read("river-request.json"); world.initialize(seed);
            byte[] before = Files.readAllBytes(world.path()); rejects(() -> WorldContinuation.run(world, new ExperienceEngine(), request, root, 32)); unchanged(world.path(), before);
        });
        run("无效活动与起始预算不会改变世界", () -> {
            Fixture f = fixture("river"); ObjectNode invalid = f.request.deepCopy(); ((ObjectNode) invalid.path("activities").get(0)).put("actor", "@author");
            byte[] before = Files.readAllBytes(f.world.path()); rejects(() -> WorldContinuation.run(f.world, new ExperienceEngine(), invalid, f.root.resolve("bad"), 32)); rejects(() -> run(f, 0)); unchanged(f.world.path(), before);
        });
        ObjectNode report = JSON.createObjectNode(); int passed = 0; for (JsonNode row : RESULTS) if (row.path("passed").asBoolean()) passed++;
        report.put("passed", passed).put("failed", RESULTS.size() - passed).set("checks", RESULTS);
        write(Path.of(args[0]), report); System.out.println(JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report));
        if (passed != RESULTS.size()) System.exit(1);
    }

    private static Fixture fixture(String name) throws Exception {
        Path root = Files.createTempDirectory("continuation-" + name + "-"); ObjectNode seed = read(name + "-seed.json"), request = read(name + "-request.json");
        WorldStore world = new WorldStore(root.resolve("world.json")); world.initialize(seed); return new Fixture(root, world, request, seed);
    }
    private static Fixture rebuilt(Fixture old) throws Exception {
        Path root = Files.createTempDirectory("continuation-var-"); WorldStore world = new WorldStore(root.resolve("world.json")); world.initialize(old.seed);
        return new Fixture(root, world, old.request, old.seed);
    }
    private static Fixture stateChoiceFixture(String name, boolean tied) throws Exception {
        Fixture f = fixture(name);
        ArrayNode rules = (ArrayNode) f.seed.path("characters").get(0).path("privateProfile").path("responsePolicy").path("rules");
        ObjectNode alternate = rules.get(0).deepCopy();
        alternate.put("id", "alternate-choice").put("priority", rules.get(0).path("priority").asInt() - (tied ? 0 : 1));
        alternate.put("description", "另一条本人依据完整的候选，核对规则选择的一致性"); rules.add(alternate);
        for (JsonNode row : f.request.path("activities").get(1).path("components"))
            if (row.path("stage").asText().equals("select")) ((ObjectNode) row).put("id", "test-state-selection");
        return rebuilt(f);
    }
    private static ExperienceEngine choosingState(String ruleId) {
        ExperienceEngine engine = new ExperienceEngine();
        engine.register(new ExperienceComponent() {
            public String id() { return "test-state-selection"; } public String version() { return "1"; }
            public Stage stage() { return Stage.SELECT; }
            public ObjectNode run(Context context) {
                return JSON.createObjectNode().put("status", "ready").put("candidateId", ruleId).put("reason", "定向检查替换选择组件");
            }
        });
        return engine;
    }
    private static void rejectedStateChoice(boolean tied) throws Exception {
        for (String name : List.of("river", "workshop")) {
            Fixture f = stateChoiceFixture(name, tied); ExperienceEngine engine = choosingState("alternate-choice"); Path episode = f.root.resolve("episode");
            WorldContinuation.run(f.world, engine, f.request, episode, 1);
            byte[] before = Files.readAllBytes(f.world.path());
            rejects(() -> WorldContinuation.run(f.world, engine, f.request, episode, 2)); unchanged(f.world.path(), before);
            equal(f.world.history().path("events").size(), 1, "拒绝错误选择时不新增正式事件：" + name);
            JsonNode record = JSON.readTree(episode.resolve("turn-002.json").toFile());
            check(!record.has("prepared") && !record.has("receipt"), "错误选择在准备正式行为前停止：" + name);
            equal(record.path("stages").get(1).path("output").path("candidates").size(), 2, "两条候选都有本人依据");
            equal(record.path("stages").get(2).path("output").path("candidateId").asText(), "alternate-choice", "保存实际选择");
            equal(record.path("stages").get(3).path("stage").asText(), "resolve", "在转换阶段拒绝");
            check(record.path("error").path("message").asText().contains("状态回应选择必须"), "保存明确的规则一致性拒绝原因");
        }
    }
    private static void acceptedStateChoice(boolean tied) throws Exception {
        for (String name : List.of("river", "workshop")) {
            Fixture f = stateChoiceFixture(name, tied); Path episode = f.root.resolve("episode");
            JsonNode done = WorldContinuation.run(f.world, choosingState("submit-on-location"), f.request, episode, 32);
            equal(done.path("status").asText(), "quiescent", "合法选择继续完成：" + name);
            equal(f.world.history().path("events").size(), 5, "仍是五笔正式事件：" + name);
            check(f.world.history().path("events").get(1).path("proposal").path("reason").asText().contains("选择submit-on-location"), "正式行为执行选中的规则");
            byte[] before = Files.readAllBytes(f.world.path());
            equal(WorldContinuation.run(new WorldStore(f.world.path()), choosingState("submit-on-location"), f.request, episode, 1), done, "重开后复用合法选择及原完成结果");
            unchanged(f.world.path(), before);
        }
    }
    private static JsonNode run(Fixture f, int budget) throws Exception { return WorldContinuation.run(f.world, new ExperienceEngine(), f.request, f.root.resolve("episode"), budget); }
    private static ObjectNode read(String name) throws Exception { return (ObjectNode) JSON.readTree(examples.resolve(name).toFile()); }
    private static JsonNode seedFact(ObjectNode seed, String id) { for (JsonNode f : seed.path("facts")) if (f.path("id").asText().equals(id)) return f; throw new AssertionError(id); }
    private static JsonNode fact(WorldStore world, String id) throws Exception { for (JsonNode f : world.view("@author").path("facts")) if (f.path("id").asText().equals(id)) return f.get("value"); throw new AssertionError(id); }
    private static JsonNode known(JsonNode view, String id) { for (JsonNode k : view.path("knowledge")) if (k.path("factId").asText().equals(id)) return k; throw new AssertionError("未找到知识：" + id); }
    private static void positive(Fixture f, JsonNode report, String actor, String responder, String idle, String state, Object value, int quantity) throws Exception {
        equal(report.path("status").asText(), "quiescent", "衔接耗尽"); equal(report.path("revision").asInt(), 5, "五次实际提交");
        equal(fact(f.world, state), JSON.valueToTree(value), "办理结果"); equal(fact(f.world, "resource-quantity").asInt(), quantity, "不消耗未声明资源");
        equal(fact(f.world, "further-permission").asBoolean(), false, "单项办理不授予后续许可");
        JsonNode view = f.world.view(actor); equal(known(view, state).path("source").asText(), "@world", "本人到场取得现场依据");
        equal(report.path("outcomes").path("inspect").path("status").asText(), "satisfied", "目标满足有实际投影依据");
        check(f.world.view(idle).path("knowledge").isEmpty(), "未参与者没有自动得知");
        check(!view.has("facts") && !view.has("laws"), "人物没有作者目录");
        check(view.path("knowledge").findValuesAsText("factId").stream().noneMatch("private-origin"::equals), "幕后资料仍未知");
        check(!f.world.view(responder).has("entities"), "回应者仍是人物投影");
        equal(f.world.inspect().path("worldTime").path("year").asInt(), 100, "没有隐含时间推进");
    }
    private static void write(Path path, JsonNode value) throws Exception { Files.createDirectories(path.toAbsolutePath().getParent()); JSON.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), value); }
    private static void unchanged(Path path, byte[] before) throws Exception { check(Arrays.equals(before, Files.readAllBytes(path)), "世界文件必须保持原字节"); }
    private static void rejects(Checked action) throws Exception { try { action.run(); } catch (IllegalArgumentException | IllegalStateException | java.io.IOException expected) { return; } throw new AssertionError("非法操作未拒绝"); }
    private static void run(String name, Checked action) { ObjectNode row = RESULTS.addObject().put("name", name); try { action.run(); row.put("passed", true); } catch (Exception | AssertionError failure) { row.put("passed", false).put("error", failure.getClass().getSimpleName() + ": " + failure.getMessage()); } }
    private static void equal(Object actual, Object expected, String why) { check(actual instanceof JsonNode a && expected instanceof JsonNode b ? canonical(a).equals(canonical(b)) : Objects.equals(actual, expected), why + "；实际=" + actual + "；预期=" + expected); }
    private static void check(boolean condition, String why) { if (!condition) throw new AssertionError(why); }
    @FunctionalInterface private interface Checked { void run() throws Exception; }
}
