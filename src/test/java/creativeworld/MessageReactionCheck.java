package creativeworld;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import static creativeworld.ExperienceComponent.Stage;

/** 实际消息与声明策略的事务验收；不调用模型、不评价心理或正文质量。 */
public final class MessageReactionCheck {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<ObjectNode> RESULTS = new ArrayList<>();
    private static Path examples, testRoot;
    private static int number;

    public static void main(String[] args) throws Exception {
        examples = args.length > 1 ? Path.of(args[1]) : Path.of("examples");
        if (!Files.exists(examples.resolve("message-reaction/genesis.json"))) examples = Path.of("creative-world-engine/examples");
        testRoot = Files.createTempDirectory("creative-world-message-reaction-");

        run("完整六地点世界中五次实际行动，按本人前提和优先级回应而不放行", () -> {
            Fixture f = fresh(); JsonNode initial = f.store.view("@author").deepCopy();
            equal(initial.path("places").size(), 6, "保持上一轮六地点背景");
            equal(initial.path("characters").size(), 6, "保持六个人物");
            equal(initial.path("facts").size(), 35, "没有为回应编造新事实");
            discover(f); JsonNode observation = knowledge(f.store.view("apprentice"), "cargo-handoff-status").deepCopy();
            receive(f); ObjectNode input = response(f, "first-response"), original = input.deepCopy();
            ObjectNode record = new ExperienceEngine().run(f.store, input, next("response"));
            equal(record.path("status").asText(), "completed", "五阶段消息回应完成");
            JsonNode candidates = stage(record, Stage.CANDIDATES).path("output").path("candidates");
            equal(candidates.size(), 2, "保留所有完整规则，不只取第一个");
            equal(stage(record, Stage.SELECT).path("output").path("candidateId").asText(), "explain-held-cargo", "较高优先级完整规则被选中");
            equal(f.store.history().path("events").size(), 5, "询问、前往、观察、取得消息、回应共五次正式行动");
            JsonNode proposal = f.store.history().path("events").get(4).path("proposal");
            equal(proposal.path("actor").asText(), "keeper", "回应主体是保管员");
            equal(proposal.path("effects").size(), 3, "两项分享和一条关系在同一事件");
            for (int i = 0; i < 2; i++) equal(proposal.path("effects").get(i).path("type").asText(), "knowledge.share", "使用本人分享效果");
            JsonNode permission = knowledge(f.store.view("apprentice"), "cargo-pickup-permission");
            check(permission.path("value").isBoolean() && !permission.path("value").asBoolean(), "false是有效已知条件");
            equal(permission.path("source").asText(), "keeper", "分享记录实际发言者来源");
            equal(permission.path("revision").asInt(), 5, "接收版本是正式回应事件");
            equal(knowledge(f.store.view("apprentice"), "cargo-handoff-rule").path("source").asText(), "keeper", "已有初始规则重新分享留下新来源");
            equal(knowledge(f.store.view("apprentice"), "cargo-handoff-status"), observation, "说明没有刷新学徒先前现场快照");
            equal(relation(f.store.view("keeper"), "apprentice").path("value"), policy(seed()).path("rules").get(1).path("relationshipValue"), "仅执行作者给定的关系回应，不声称取得原始现场证据链");
            check(!relation(f.store.view("keeper"), "apprentice").path("basis").asText().isBlank(), "关系有本次消息与规则依据");
            equal(record.path("finalView"), f.store.viewAt("keeper", 5), "反馈绑定实际提交版本");
            equal(input, original, "不修改调用者请求");
            noBusinessChange(initial, f.store.view("@author")); privacy(f, record.path("initialView"));
            privacy(f, f.store.view("apprentice"));
        });

        run("没有消息、条件未知或已知不符均停住，不伪造动作", () -> {
            Fixture absent = fresh(); ObjectNode missing = response(absent, "no-message");
            ((ObjectNode) missing.path("scenario").path("message")).put("factId", "unreceived-message");
            stops(absent, missing, "messageUnknown");
            ObjectNode unknownSeed = seed(); onlyHighRule(unknownSeed);
            ArrayNode known = (ArrayNode) fact(unknownSeed, "cargo-pickup-permission").path("knownBy");
            for (int i = known.size() - 1; i >= 0; i--) if (known.get(i).asText().equals("keeper")) known.remove(i);
            Fixture unknown = fresh(unknownSeed); discover(unknown); receive(unknown); stops(unknown, response(unknown, "unknown-condition"), "conditionUnknown");
            ObjectNode mismatchSeed = seed(); onlyHighRule(mismatchSeed);
            ((ObjectNode) policy(mismatchSeed).path("rules").get(0).path("conditions").get(0)).put("equals", true);
            Fixture mismatch = fresh(mismatchSeed); discover(mismatch); receive(mismatch); stops(mismatch, response(mismatch, "unmet-condition"), "conditionUnmet");
            ObjectNode noRuleSeed = seed(); policy(noRuleSeed).putArray("rules");
            Fixture noRule = fresh(noRuleSeed); discover(noRule); receive(noRule); stops(noRule, response(noRule, "no-rule"), "noMatchingRule");
            ObjectNode noShareSeed = seed(); onlyHighRule(noShareSeed);
            ((ObjectNode) policy(noShareSeed).path("rules").get(0)).putArray("shareFactIds").add("registry-copy-origin");
            Fixture noShare = fresh(noShareSeed); discover(noShare); receive(noShare); stops(noShare, response(noShare, "no-known-share"), "shareUnknown");
        });

        run("作者隐藏真值不会使人物未知条件成立，旧消息也不冒充当前真相", () -> {
            ObjectNode seed = seed(); ObjectNode secret = fact(seed, "registry-copy-origin"); secret.put("value", true);
            ((ObjectNode) policy(seed).path("rules").get(2).path("conditions").get(0)).put("equals", true);
            Fixture f = fresh(seed); discover(f);
            f.store.apply(proposal(f, "author-real-state-changed", "@author", change("cargo-handoff-status", "held-awaiting-registration", "registered-awaiting-permission")));
            receive(f); ObjectNode record = new ExperienceEngine().run(f.store, response(f, "old-message-response"), next("old-message"));
            equal(stage(record, Stage.SELECT).path("output").path("candidateId").asText(), "explain-held-cargo", "以收到旧状态回应，不使用作者新真值或隐藏条件");
            equal(knowledge(f.store.view("keeper"), "cargo-handoff-status").path("value").asText(), "held-awaiting-registration", "收到的是学徒旧快照");
            equal(fact(f.store.view("@author"), "cargo-handoff-status").path("value").asText(), "registered-awaiting-permission", "世界真值未被旧消息改写");
            check(!hasKnowledge(f.store.view("keeper"), "registry-copy-origin"), "选择不会授予幕后事实");
            for (JsonNode row : stage(record, Stage.CANDIDATES).path("output").path("candidates")) check(!row.path("id").asText().equals("hidden-note-must-not-drive-response"), "高优先级未知条件不能抢选");
            check(!record.path("initialView").has("facts"), "实际阶段只收到人物资料");
        });

        run("转述与分享保留证据年份，新的接收时间不洗新旧快照", () -> {
            Fixture f = fresh(); discover(f); JsonNode observed = knowledge(f.store.view("apprentice"), "cargo-handoff-status").deepCopy();
            f.store.apply(proposal(f, "ten-years-later", "@author", effect("time.advance").put("expectedYear", 100).put("toYear", 110)));
            receive(f); JsonNode received = knowledge(f.store.view("keeper"), "cargo-handoff-status");
            equal(received.path("learnedAtYear").asInt(), 110, "收到于当前年"); equal(received.path("asOfYear").asInt(), 100, "证据仍来自原观察年");
            ObjectNode record = new ExperienceEngine().run(f.store, response(f, "old-year-response"), next("old-year"));
            equal(record.path("status").asText(), "completed", "明确旧消息仍可按本人策略回应");
            JsonNode shared = knowledge(f.store.view("apprentice"), "cargo-pickup-permission");
            equal(shared.path("learnedAtYear").asInt(), 110, "新分享获取时间"); equal(shared.path("asOfYear").asInt(), 100, "分享继承保管员证据时间");
            equal(knowledge(f.store.viewAt("apprentice", 3), "cargo-handoff-status"), observed, "旧历史视图不被后来时间抹去");
        });

        run("精确来源与接收版本绑定，来源离开同地后不能直接回应", () -> {
            Fixture overwritten = fresh(); discover(overwritten); receive(overwritten); ObjectNode stale = response(overwritten, "stale-message");
            receive(overwritten); stale.put("expectedRevision", revision(overwritten)); stops(overwritten, stale, "messageChanged");
            unchangedReject(overwritten, () -> overwritten.store.step("message-response", moduleInput(stale, "explain-held-cargo")));
            Fixture away = fresh(); discover(away); receive(away); ObjectNode input = response(away, "source-away");
            ObjectNode travel = action(away, "apprentice-leaves", "apprentice").put("to", "wharf"); away.store.step("travel", travel);
            input.put("expectedRevision", revision(away)); stops(away, input, "sourceNotNearby");
            ObjectNode falseSource = response(away, "author-is-not-speaker"); ((ObjectNode) falseSource.path("scenario").path("message")).put("sourcePersonId", "@world");
            stops(away, falseSource, "invalidMessageSource");
        });

        run("分享必须由本人向同地他人说出已有认识，非法来源与未知知识拒绝", () -> {
            Fixture f = fresh(); discover(f); receive(f);
            unchangedReject(f, () -> f.store.apply(proposal(f, "spoof-speaker", "apprentice", share("keeper", "apprentice", "cargo-pickup-permission"))));
            unchangedReject(f, () -> f.store.apply(proposal(f, "unknown-shared", "keeper", share("keeper", "apprentice", "registry-copy-origin"))));
            unchangedReject(f, () -> f.store.apply(proposal(f, "self-share", "keeper", share("keeper", "keeper", "cargo-pickup-permission"))));
            unchangedReject(f, () -> f.store.apply(proposal(f, "far-recipient", "keeper", share("keeper", "smith", "cargo-pickup-permission"))));
            check(!hasKnowledge(f.store.view("apprentice"), "cargo-pickup-permission"), "所有拒绝不产生许可认识");
        });

        run("合法分享与非法关系组合整体回滚，没有半条新认识", () -> {
            Fixture f = fresh(); discover(f); receive(f);
            ObjectNode badRelation = effect("relationship.change").put("from", "keeper").put("to", "apprentice")
                .put("expectedValue", "不存在的关系").put("value", "新关系").put("basis", "隔离事务反例");
            unchangedReject(f, () -> f.store.apply(proposal(f, "partial-share-must-not-save", "keeper", share("keeper", "apprentice", "cargo-pickup-permission"), badRelation)));
            check(!hasKnowledge(f.store.view("apprentice"), "cargo-pickup-permission"), "先执行分享也不会半提交");
            equal(relation(f.store.view("keeper"), "apprentice").path("value").asText(), "只按行会手续接待", "旧关系保持");
        });

        run("模块正式执行再核规则，畸形输入与无权替别人发言均拒绝", () -> {
            Fixture f = fresh(); discover(f); receive(f); ObjectNode valid = moduleInput(response(f, "direct-base"), "explain-held-cargo");
            List<ObjectNode> invalid = new ArrayList<>();
            ObjectNode bad = valid.deepCopy(); bad.put("ruleId", "not-a-rule"); invalid.add(bad);
            bad = valid.deepCopy(); bad.put("actor", "apprentice"); invalid.add(bad);
            bad = valid.deepCopy(); bad.put("worldId", "foreign-world"); invalid.add(bad);
            bad = valid.deepCopy(); bad.remove("message"); invalid.add(bad);
            bad = valid.deepCopy(); ((ObjectNode) bad.path("message")).put("receivedRevision", 999); invalid.add(bad);
            bad = valid.deepCopy(); bad.put("extraAuthority", true); invalid.add(bad);
            for (ObjectNode value : invalid) unchangedReject(f, () -> f.store.step("message-response", value));
            ObjectNode badSeed = seed(); ((ObjectNode) policy(badSeed).path("rules").get(0)).put("priority", "highest");
            Fixture malformed = fresh(badSeed); discover(malformed); receive(malformed);
            unchangedReject(malformed, () -> new ExperienceEngine().run(malformed.store, response(malformed, "malformed-policy"), next("malformed-policy")));
        });

        run("同请求恢复与重开返回原回执，行为版本不能冒领或替换", () -> {
            Fixture f = fresh(); discover(f); receive(f); ObjectNode input = response(f, "repeat-response"); Path path = next("repeat");
            ObjectNode record = new ExperienceEngine().run(f.store, input, path); byte[] bytes = Files.readAllBytes(f.path);
            WorldStore reopened = new WorldStore(f.path); ObjectNode again = new ExperienceEngine().run(reopened, input, path);
            equal(again.path("receipt"), record.path("receipt"), "重开演绎返回原权威回执");
            ObjectNode formalInput = (ObjectNode) record.path("prepared").path("submission").path("input");
            equal(reopened.step("message-response", formalInput), record.path("receipt"), "旧调用固定版本1可恢复显式版本1事件");
            equal(reopened.receiptFor("message-response", "1", formalInput), record.path("receipt"), "显式版本查询同一回执");
            unchangedReject(f, () -> reopened.step("message-response", "2", formalInput));
            check(Arrays.equals(bytes, Files.readAllBytes(f.path)), "所有重复或错版本均不重写世界");
            equal(reopened.history().path("events").size(), 5, "没有重复回应事件");
            equal(reopened.view("keeper"), f.store.view("keeper"), "重放投影保持");
        });

        run("真实消息管线反馈失败后只补反馈，先前世界提交与阶段结果保留", () -> {
            Fixture f = fresh(); discover(f); receive(f); Map<Stage, AtomicInteger> calls = counters();
            ExperienceEngine engine = instrumented("1", calls, (base, context, count) -> {
                if (base.stage() == Stage.FEEDBACK && count == 1) throw new IllegalStateException("模拟反馈失败");
                return base.run(context);
            });
            ObjectNode input = response(f, "recover-feedback"); useWrappers(input, "1"); Path path = next("feedback");
            rejects(() -> engine.run(f.store, input, path)); JsonNode failed = JSON.readTree(path.toFile());
            equal(failed.path("status").asText(), "feedbackFailed", "失败真实记录");
            equal(failed.path("receipt").path("revision").asInt(), 5, "提交回执仍在");
            equal(knowledge(f.store.view("apprentice"), "cargo-pickup-permission").path("source").asText(), "keeper", "失败不丢已正式分享");
            byte[] committed = Files.readAllBytes(f.path); ObjectNode recovered = engine.run(new WorldStore(f.path), input, path);
            equal(recovered.path("status").asText(), "completed", "可恢复完成");
            for (Stage phase : Stage.values()) equal(calls.get(phase).get(), phase == Stage.FEEDBACK ? 2 : 1, "只重试反馈：" + phase);
            equal(recovered.path("receipt"), failed.path("receipt"), "恢复核对同一回执");
            equal(recovered.path("finalView"), f.store.viewAt("keeper", 5), "补反馈绑定旧提交而非新猜结果");
            check(Arrays.equals(committed, Files.readAllBytes(f.path)), "恢复不重提世界事件");
        });

        run("阶段快照隔离及版本绑定，不允许resolve偷换行为版本", () -> {
            Fixture f = fresh(); discover(f); receive(f); Map<Stage, AtomicInteger> calls = counters();
            ExperienceEngine engine = instrumented("2", calls, (base, context, count) -> {
                ObjectNode before = context.actorView(); context.actorView().remove("knowledge"); context.request().remove("actor"); context.outputs().removeAll();
                equal(context.actorView(), before, "组件只改独立快照"); return base.run(context);
            });
            ObjectNode input = response(f, "component-v2"); useWrappers(input, "2"); Path path = next("v2");
            ObjectNode record = engine.run(f.store, input, path); equal(record.path("status").asText(), "completed", "同策略新版阶段实际执行");
            for (Stage phase : Stage.values()) { equal(calls.get(phase).get(), 1, "五阶段都运行"); equal(stage(record, phase).path("componentVersion").asText(), "2", "阶段版本明确"); }
            ObjectNode otherVersion = input.deepCopy(); useWrappers(otherVersion, "1");
            unchangedReject(f, () -> engine.run(f.store, otherVersion, path));
            Fixture stolen = fresh(); discover(stolen); receive(stolen); ExperienceEngine malicious = new ExperienceEngine();
            malicious.register(new ExperienceComponent() {
                public String id() { return "steal-action-version"; } public String version() { return "1"; } public Stage stage() { return Stage.RESOLVE; }
                public ObjectNode run(Context context) { ObjectNode output = MessageReactionComponents.all().get(Stage.RESOLVE.ordinal()).run(context); ((ObjectNode) output.path("action")).put("moduleVersion", "2"); return output; }
            });
            ObjectNode stolenInput = response(stolen, "steal-action-version"); ((ObjectNode) stolenInput.path("components").get(Stage.RESOLVE.ordinal())).put("id", "steal-action-version");
            unchangedReject(stolen, () -> malicious.run(stolen.store, stolenInput, next("stolen-version")));
        });

        run("换人物地点和物品id后复用同一回应管线，没有阿禾与铁料硬编码", () -> {
            String text = JSON.writeValueAsString(seed());
            Map<String, String> ids = Map.of("apprentice", "buyer", "keeper", "herbalist", "boatman", "courier", "wharf", "quay", "warehouse", "herb-store", "cargo-location", "herb-location", "cargo-handoff-status", "herb-handoff", "cargo-pickup-permission", "herb-permission", "cargo-handoff-rule", "herb-rule");
            for (Map.Entry<String, String> entry : ids.entrySet()) text = text.replace("\"" + entry.getKey() + "\"", "\"" + entry.getValue() + "\"");
            ObjectNode seed = (ObjectNode) JSON.readTree(text); seed.put("worldId", "message-herb-town"); Fixture f = fresh(seed);
            f.store.step("ask", action(f, "herb-location-ask", "buyer").put("target", "courier").put("factId", "herb-location"));
            f.store.step("travel", action(f, "herb-travel", "buyer").put("to", "herb-store"));
            f.store.step("observe", action(f, "herb-observe", "buyer").put("factId", "herb-handoff"));
            f.store.step("ask", action(f, "herbalist-receives", "herbalist").put("target", "buyer").put("factId", "herb-handoff"));
            ObjectNode input = read("response.json"); input.put("worldId", f.worldId).put("actor", "herbalist").put("requestId", "herb-response").put("expectedRevision", revision(f));
            ((ObjectNode) input.path("scenario").path("message")).put("factId", "herb-handoff").put("sourcePersonId", "buyer").put("receivedRevision", knowledge(f.store.view("herbalist"), "herb-handoff").path("revision").asInt());
            ObjectNode record = new ExperienceEngine().run(f.store, input, next("herb")); equal(record.path("status").asText(), "completed", "另一个世界仍可执行");
            equal(knowledge(f.store.view("buyer"), "herb-permission").path("source").asText(), "herbalist", "新的实际发言者来源");
            equal(f.store.history().path("events").get(4).path("proposal").path("actor").asText(), "herbalist", "正式主体不是旧固定id");
            equal(fact(f.store.view("@author"), "herb-location").path("value").asText(), "herb-store", "回应不搬走另一种物品");
        });

        ObjectNode report = JSON.createObjectNode(); long passed = RESULTS.stream().filter(row -> row.path("passed").asBoolean()).count();
        report.put("checks", RESULTS.size()).put("passed", passed).put("failed", RESULTS.size() - passed); report.set("results", JSON.valueToTree(RESULTS));
        report.put("actualModelCalled", false).put("formalServiceUsed", false).put("proseQualityEvaluated", false).put("autonomousPsychologyEvaluated", false).put("cargoDelivered", false);
        report.put("evidenceLimit", "仅验收本人消息与作者声明策略、分享快照来源和年份、同地身份、完整事务、版本和反馈恢复；不证明自主心理、自由对话、登记放行或小说质量。");
        report.put("isolatedTestDirectory", testRoot.toString()).put("examplesDirectory", examples.toAbsolutePath().toString());
        if (args.length > 0) { Path output = Path.of(args[0]).toAbsolutePath(); Files.createDirectories(output.getParent()); JSON.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report); }
        System.out.println(JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report)); if (passed != RESULTS.size()) System.exit(1);
    }

    private record Fixture(Path path, WorldStore store, String worldId) {}
    private static Fixture fresh() throws Exception { return fresh(seed()); }
    private static Fixture fresh(ObjectNode seed) throws Exception { Path path = next("world"); WorldStore store = new WorldStore(path); store.initialize(seed); return new Fixture(path, store, seed.path("worldId").asText()); }
    private static ObjectNode read(String name) throws Exception { return (ObjectNode) JSON.readTree(examples.resolve("message-reaction").resolve(name).toFile()); }
    private static ObjectNode seed() throws Exception { return (ObjectNode) new WorldGenesis().build(read("genesis.json")).path("seed").deepCopy(); }
    private static Path next(String kind) { return testRoot.resolve(kind + "-" + ++number + ".json"); }
    private static int revision(Fixture f) throws Exception { return f.store.inspect().path("revision").asInt(); }
    private static ObjectNode action(Fixture f, String id, String actor) throws Exception { return JSON.createObjectNode().put("schemaVersion", 1).put("worldId", f.worldId).put("requestId", id).put("expectedRevision", revision(f)).put("actor", actor); }
    private static void discover(Fixture f) throws Exception {
        ObjectNode original = read("apprentice-goal.json"); original.put("worldId", f.worldId);
        ExperienceEngine engine = new ExperienceEngine();
        for (String module : List.of("ask", "travel", "observe")) {
            ObjectNode input = original.deepCopy().put("requestId", "discover-" + module).put("expectedRevision", revision(f));
            ObjectNode record = engine.run(f.store, input, next("discover-" + module)); equal(record.path("status").asText(), "completed", "核实一步完成：" + module);
            equal(record.path("prepared").path("submission").path("module").asText(), module, "根据本人资料生成实际行动");
        }
        equal(f.store.view("apprentice").path("self").path("place").asText(), "warehouse", "学徒实际到达");
        equal(knowledge(f.store.view("apprentice"), "cargo-handoff-status").path("source").asText(), "@world", "学徒实际现场观察");
    }
    private static void receive(Fixture f) throws Exception { f.store.step("ask", action(f, "keeper-receives-" + revision(f), "keeper").put("target", "apprentice").put("factId", "cargo-handoff-status")); }
    private static ObjectNode response(Fixture f, String id) throws Exception {
        ObjectNode input = read("response.json"); input.put("worldId", f.worldId).put("requestId", id).put("expectedRevision", revision(f));
        int received = hasKnowledge(f.store.view("keeper"), "cargo-handoff-status") ? knowledge(f.store.view("keeper"), "cargo-handoff-status").path("revision").asInt() : 0;
        ((ObjectNode) input.path("scenario").path("message")).put("receivedRevision", received); return input;
    }
    private static ObjectNode moduleInput(ObjectNode request, String rule) {
        ObjectNode input = JSON.createObjectNode(); for (String field : List.of("schemaVersion", "worldId", "requestId", "expectedRevision", "actor")) input.set(field, request.get(field).deepCopy());
        input.set("message", request.path("scenario").path("message").deepCopy()); input.put("ruleId", rule); return input;
    }
    private static ObjectNode effect(String type) { return JSON.createObjectNode().put("type", type); }
    private static ObjectNode share(String from, String to, String fact) { return effect("knowledge.share").put("from", from).put("to", to).put("factId", fact); }
    private static ObjectNode change(String fact, String before, String after) { return effect("fact.change").put("factId", fact).put("expectedValue", before).put("value", after); }
    private static ObjectNode proposal(Fixture f, String id, String actor, ObjectNode... effects) throws Exception {
        ObjectNode value = action(f, id, actor).put("reason", "隔离验收明确效果及消息边界，不推断额外后果"); ArrayNode rows = value.putArray("effects"); for (ObjectNode effect : effects) rows.add(effect); return value;
    }
    private static ObjectNode fact(JsonNode seedOrView, String id) { return (ObjectNode) by(seedOrView.path("facts"), "id", id); }
    private static ObjectNode policy(JsonNode seed) { return (ObjectNode) by(seed.path("characters"), "id", "keeper").path("privateProfile").path("responsePolicy"); }
    private static void onlyHighRule(JsonNode seed) { ObjectNode value = policy(seed); JsonNode rule = value.path("rules").get(1).deepCopy(); value.putArray("rules").add(rule); }
    private static JsonNode knowledge(JsonNode view, String id) { return by(view.path("knowledge"), "factId", id); }
    private static boolean hasKnowledge(JsonNode view, String id) { for (JsonNode row : view.path("knowledge")) if (row.path("factId").asText().equals(id)) return true; return false; }
    private static JsonNode relation(JsonNode view, String id) { return by(view.path("relationships"), "to", id); }
    private static JsonNode by(JsonNode rows, String field, String value) { for (JsonNode row : rows) if (row.path(field).asText().equals(value)) return row; throw new AssertionError("缺少 " + field + ":" + value); }
    private static JsonNode stage(JsonNode record, Stage stage) { for (JsonNode row : record.path("stages")) if (row.path("stage").asText().equals(stage.key()) && row.path("status").asText().equals("completed")) return row; throw new AssertionError("阶段未完成：" + stage); }
    private static void stops(Fixture f, ObjectNode input, String kind) throws Exception {
        byte[] bytes = Files.readAllBytes(f.path); JsonNode history = f.store.history().deepCopy(); ObjectNode record = new ExperienceEngine().run(f.store, input, next("needs-input"));
        equal(record.path("status").asText(), "needsInput", "资料不足停住"); check(!record.has("receipt") && !record.has("prepared"), "不编造正式动作");
        boolean found = false; for (JsonNode row : record.path("stages")) for (JsonNode gap : row.path("output").path("gaps")) if (gap.path("kind").asText().equals(kind)) found = true;
        check(found, "有具体缺口：" + kind); equal(f.store.history(), history, "停住不增加历史"); check(Arrays.equals(bytes, Files.readAllBytes(f.path)), "停住正式文件不变");
    }
    private static void unchangedReject(Fixture f, Checked action) throws Exception {
        byte[] bytes = Files.readAllBytes(f.path); JsonNode history = f.store.history().deepCopy(), author = f.store.view("@author").deepCopy(); rejects(action);
        check(Arrays.equals(bytes, Files.readAllBytes(f.path)), "拒绝世界字节保持"); equal(f.store.history(), history, "拒绝历史保持"); equal(f.store.view("@author"), author, "拒绝全部状态保持");
    }
    private static void noBusinessChange(JsonNode before, JsonNode after) {
        for (String id : List.of("cargo-location", "cargo-registration-state", "cargo-pickup-permission", "iron-quantity")) equal(fact(after, id).path("value"), fact(before, id).path("value"), "回应不暗改业务事实：" + id);
        equal(before.path("places"), after.path("places"), "地理不变"); for (JsonNode person : before.path("characters")) { JsonNode current = by(after.path("characters"), "id", person.path("id").asText()); equal(current.path("name"), person.path("name"), "姓名不变"); equal(current.path("privateProfile"), person.path("privateProfile"), "私密资料未改写"); }
        for (JsonNode fact : before.path("facts")) if (fact.path("mode").asText().equals("fixed")) equal(fact(after, fact.path("id").asText()).path("value"), fact.path("value"), "固定事实不变");
    }
    private static void privacy(Fixture f, JsonNode view) throws Exception {
        check(!view.has("facts") && !view.has("characters") && !view.has("entities") && !view.has("laws"), "人物不携带作者目录");
        for (JsonNode other : view.path("nearby")) check(!other.has("privateProfile"), "同地人物只有公开资料");
        String text = JSON.writeValueAsString(view); JsonNode author = f.store.view("@author");
        for (JsonNode person : author.path("characters")) if (!person.path("id").asText().equals(view.path("actor").asText())) for (String field : List.of("goal", "concern")) {
            String secret = person.path("privateProfile").path(field).asText(); if (!secret.isBlank()) check(!text.contains(secret), "别人的私有" + field + "不泄漏");
        }
        check(!text.contains(fact(author, "registry-copy-origin").path("value").asText()), "作者幕后原因不进入人物资料");
    }
    private static Map<Stage, AtomicInteger> counters() { Map<Stage, AtomicInteger> values = new EnumMap<>(Stage.class); for (Stage stage : Stage.values()) values.put(stage, new AtomicInteger()); return values; }
    private static ExperienceEngine instrumented(String version, Map<Stage, AtomicInteger> calls, Hook hook) {
        ExperienceEngine engine = new ExperienceEngine(false); for (ExperienceComponent base : MessageReactionComponents.all()) engine.register(new ExperienceComponent() {
            public String id() { return "test-message-" + base.stage().key(); } public String version() { return version; } public Stage stage() { return base.stage(); }
            public ObjectNode run(Context context) { return hook.run(base, context, calls.get(base.stage()).incrementAndGet()); }
        }); return engine;
    }
    private static void useWrappers(ObjectNode input, String version) { for (JsonNode row : input.path("components")) ((ObjectNode) row).put("id", "test-message-" + row.path("stage").asText()).put("version", version); }
    private static void rejects(Checked action) throws Exception { try { action.run(); } catch (IllegalArgumentException | IllegalStateException | java.io.IOException expected) { return; } throw new AssertionError("非法操作未拒绝"); }
    private static void run(String name, Checked action) { ObjectNode result = JSON.createObjectNode().put("name", name); try { action.run(); result.put("passed", true); } catch (Exception | AssertionError failure) { result.put("passed", false).put("error", failure.getClass().getSimpleName() + ": " + failure.getMessage()); } RESULTS.add(result); }
    private static void equal(Object actual, Object expected, String why) { boolean same = actual instanceof JsonNode a && expected instanceof JsonNode b ? Protocol.canonical(a).equals(Protocol.canonical(b)) : java.util.Objects.equals(actual, expected); check(same, why + "；预期=" + expected + "；实际=" + actual); }
    private static void check(boolean condition, String why) { if (!condition) throw new AssertionError(why); }
    @FunctionalInterface private interface Checked { void run() throws Exception; }
    @FunctionalInterface private interface Hook { ObjectNode run(ExperienceComponent base, ExperienceComponent.Context context, int count); }
}
