package creativeworld;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import static creativeworld.ExperienceComponent.Stage;

/** 局部认识目标的实际承接与知情边界；不是人物心理推导或小说质量评测。 */
public final class GoalCheck {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<ObjectNode> RESULTS = new ArrayList<>();
    private static Path testRoot, examples;
    private static int sequence;

    public static void main(String[] args) throws Exception {
        testRoot = Files.createTempDirectory("creative-world-goal-check-");
        examples = args.length > 1 ? Path.of(args[1]) : Path.of("examples");
        if (args.length < 2 && !Files.exists(examples.resolve("goal-inspect.json"))) examples = Path.of("creative-world-engine", "examples");

        run("不手写候选，三次请求分别询问、沿已知路线移动、现场观察", () -> {
            Fixture f = fresh(); ExperienceEngine engine = new ExperienceEngine();
            check(!f.store.view("apprentice").path("knowledge").toString().contains("cargo-location"), "初始学徒不知道货箱位置");
            JsonNode originalFacts = f.store.view("@author").path("facts").deepCopy();
            ObjectNode first = advance(f, engine, "auto-ask"); action(first, "ask", "target", "boatman");
            equal(position(f, "apprentice"), "wharf", "询问没有直接移动");
            JsonNode hearsay = knowledge(f.store.view("apprentice"), "cargo-location");
            equal(hearsay.path("value").asText(), "warehouse", "从船工得到位置消息");
            equal(hearsay.path("source").asText(), "boatman", "传闻不能冒充现场认识");
            ObjectNode second = advance(f, engine, "auto-travel"); action(second, "travel", "to", "warehouse");
            equal(position(f, "apprentice"), "warehouse", "沿本人有依据的路线走一跳");
            equal(knowledge(f.store.view("apprentice"), "cargo-location"), hearsay, "移动没有自动更新现场认识");
            ObjectNode third = advance(f, engine, "auto-observe"); action(third, "observe", "factId", "cargo-location");
            JsonNode observed = knowledge(f.store.view("apprentice"), "cargo-location");
            equal(observed.path("source").asText(), "@world", "现场认识来自内核观察事务");
            equal(observed.path("value").asText(), "warehouse", "观察取得当时真实值");
            equal(observed.path("revision").asInt(), 3, "认识绑定实际观察事件版本");
            equal(f.store.history().path("events").size(), 3, "三次各追加一个事件");
            equal(f.store.view("@author").path("facts"), originalFacts, "查问、移动、观察没有搬走或改变货物事实");
            equal(knowledge(f.store.viewAt("apprentice", 1), "cargo-location").path("source").asText(), "boatman", "历史传闻来源没有被观察覆盖");
            equal(stage(third, Stage.FEEDBACK).path("output").path("after"), third.path("finalView"), "反馈使用实际提交后人物状态");
            check(stage(third, Stage.FEEDBACK).path("output").path("remainingGaps").size() > 0, "查清位置不消除搬运许可等剩余缺口");
        });

        run("目标已现场满足后正常停止，同记录重读不新增事件", () -> {
            Fixture f = fresh(); ExperienceEngine engine = new ExperienceEngine();
            advance(f, engine, "satisfied-ask"); advance(f, engine, "satisfied-travel"); advance(f, engine, "satisfied-observe");
            ObjectNode input = request(f, "already-satisfied"); Path path = next("satisfied"); byte[] before = Files.readAllBytes(f.path);
            ObjectNode result = engine.run(f.store, input, path);
            equal(result.path("status").asText(), "satisfied", "满足是正常终态");
            check(!result.has("prepared") && !result.has("receipt"), "满足停止没有新提交包或回执");
            check(!stage(result, Stage.CANDIDATES).path("output").path("reason").asText().isBlank(), "说明已有现场依据而非无故阻塞");
            check(stage(result, Stage.CANDIDATES).path("output").path("evidence").size() > 0, "满足判断有真实人物认识依据");
            check(Arrays.equals(before, Files.readAllBytes(f.path)), "满足请求不改变世界字节");
            equal(engine.run(f.store, input.deepCopy(), path), result, "满足记录可以重读");
            check(Arrays.equals(before, Files.readAllBytes(f.path)), "满足重读也不重复事件");
        });

        run("原始 seed 知识不是现场观察，到位后仍要执行 observe", () -> {
            ObjectNode seed = seed(); person(seed, "apprentice").put("place", "warehouse");
            ((ArrayNode) fact(seed, "cargo-location").path("knownBy")).add("apprentice"); Fixture f = fresh(seed);
            equal(knowledge(f.store.view("apprentice"), "cargo-location").path("source").asText(), "@seed", "初始认识来源");
            ObjectNode result = advance(f, new ExperienceEngine(), "seed-is-not-observation");
            action(result, "observe", "factId", "cargo-location");
            equal(knowledge(f.store.view("apprentice"), "cargo-location").path("source").asText(), "@world", "必须通过真实观察取得现场来源");
            equal(f.store.inspect().path("revision").asInt(), 1, "不是凭 seed 提前返回已满足");
        });

        run("世界道路存在也不能替代人物的路线认识，缺依据或 false 均不移动", () -> {
            for (String variation : List.of("no-routes", "unknown-evidence", "not-known", "false-evidence")) {
                ObjectNode seed = seed(); ((ArrayNode) fact(seed, "cargo-location").path("knownBy")).add("apprentice");
                ObjectNode profile = (ObjectNode) person(seed, "apprentice").path("privateProfile");
                if (variation.equals("no-routes")) profile.remove("knownRoutes");
                if (variation.equals("unknown-evidence")) ((ObjectNode) profile.path("knownRoutes").get(0)).put("evidenceFactId", "unheard-route");
                if (variation.equals("not-known")) ((ArrayNode) fact(seed, "known-path-wharf-warehouse").path("knownBy")).removeAll();
                if (variation.equals("false-evidence")) fact(seed, "known-path-wharf-warehouse").put("value", false);
                Fixture f = fresh(seed); byte[] bytes = Files.readAllBytes(f.path); ObjectNode result = advance(f, new ExperienceEngine(), "route-" + variation);
                equal(result.path("status").asText(), "needsInput", "无法找到本人有依据的路线：" + variation);
                check(stage(result, Stage.CANDIDATES).path("output").path("gaps").size() > 0, "停止说明缺少路线依据");
                check(!result.has("receipt") && Arrays.equals(bytes, Files.readAllBytes(f.path)), "不能使用世界隐藏道路替人物行动");
                equal(position(f, "apprentice"), "wharf", "保持原位置");
            }
        });

        run("世界真实位置发生变化不会自动更新传闻，也不会偷偷用真值改路线", () -> {
            Fixture f = fresh(); ExperienceEngine engine = new ExperienceEngine(); advance(f, engine, "rumor-first");
            JsonNode hearsay = knowledge(f.store.view("apprentice"), "cargo-location").deepCopy();
            f.store.apply(authorProposal(f, "cargo-now-elsewhere", JSON.createObjectNode().put("type", "fact.change")
                .put("factId", "cargo-location").put("expectedValue", "warehouse").put("value", "forge")));
            equal(fact((ObjectNode) f.store.view("@author"), "cargo-location").path("value").asText(), "forge", "真实位置已向前变化");
            equal(knowledge(f.store.view("apprentice"), "cargo-location"), hearsay, "旧传闻没有自动变成最新真值");
            ObjectNode result = advance(f, engine, "follow-own-rumor"); action(result, "travel", "to", "warehouse");
            equal(position(f, "apprentice"), "warehouse", "路线基于本人旧认识而非隐含全知");
            equal(knowledge(f.store.view("apprentice"), "cargo-location"), hearsay, "移动后仍没有伪造新认识");
            equal(knowledge(f.store.view("boatman"), "cargo-location").path("value").asText(), "warehouse", "来源人的旧认识同样被保存");
        });

        run("多跳路径每次只走第一跳，未向人物公开的捷径不参与搜索", () -> {
            ObjectNode seed = seed(); fact(seed, "cargo-location").put("value", "forge");
            ((ArrayNode) fact(seed, "cargo-location").path("knownBy")).add("apprentice");
            addKnownRoute(seed, "warehouse", "forge", "known-path-warehouse-forge", true, true);
            ((ArrayNode) seed.path("routes")).addObject().put("from", "wharf").put("to", "forge");
            Fixture f = fresh(seed); ExperienceEngine engine = new ExperienceEngine();
            ObjectNode first = advance(f, engine, "two-hop-first"); action(first, "travel", "to", "warehouse");
            equal(position(f, "apprentice"), "warehouse", "虽然世界有捷径，只走本人已知路径的第一跳");
            ObjectNode second = advance(f, engine, "two-hop-second"); action(second, "travel", "to", "forge");
            equal(position(f, "apprentice"), "forge", "第二次再继续下一跳");
            ObjectNode third = advance(f, engine, "two-hop-observe"); action(third, "observe", "factId", "cargo-location");
            equal(f.store.history().path("events").size(), 3, "多跳过程没有压成一次瞬移");
        });

        run("陌生人看似有私有线索但实际不知情时，询问失败不产生假交流", () -> {
            ObjectNode seed = seed(); person(seed, "boatman").put("place", "forge");
            ((ArrayNode) seed.path("relationships")).removeAll(); addPerson(seed, "stranger", "陌生旅人", "wharf");
            person(seed, "stranger").putObject("privateProfile").put("cargo-location", "warehouse").put("secret", "不能冒充认识快照");
            Fixture f = fresh(seed); ObjectNode input = request(f, "ask-uninformed-stranger"); Path path = next("stranger-failed"); byte[] bytes = Files.readAllBytes(f.path);
            rejects(() -> new ExperienceEngine().run(f.store, input, path)); JsonNode record = JSON.readTree(path.toFile());
            action(record, "ask", "target", "stranger"); equal(record.path("status").asText(), "failed", "不能把不知情包装成成功对话");
            check(!record.has("receipt") && Arrays.equals(bytes, Files.readAllBytes(f.path)), "不知情询问不落账");
            check(optionalKnowledge(f.store.view("apprentice"), "cargo-location") == null, "没有虚构不知道或地点认识");
            equal(f.store.view("apprentice").path("beliefs").size(), 0, "没有用假的信念补偿失败交流");
        });

        run("关系只影响询问优先次序，组件只能收到本人的投影资料", () -> {
            ObjectNode seed = seed(); addPerson(seed, "visitor", "先出现在列表的旅人", "wharf");
            ArrayNode people = (ArrayNode) seed.path("characters"); JsonNode visitor = people.remove(people.size() - 1); people.insert(0, visitor);
            ((ArrayNode) fact(seed, "cargo-location").path("knownBy")).add("visitor"); Fixture f = fresh(seed);
            AtomicInteger calls = new AtomicInteger(); ExperienceEngine engine = new ExperienceEngine(false);
            for (ExperienceComponent base : KnowledgeGoalComponents.all()) engine.register(new ExperienceComponent() {
                public String id() { return base.id(); }
                public String version() { return base.version(); }
                public Stage stage() { return base.stage(); }
                public ObjectNode run(Context context) {
                    calls.incrementAndGet(); ObjectNode view = context.actorView();
                    check(view.path("self").path("privateProfile").isObject(), "只提供自己的私密资料");
                    for (JsonNode nearby : view.path("nearby")) check(!nearby.has("privateProfile"), "附近人物只公开形象与位置");
                    for (String field : List.of("facts", "characters", "entities", "routes", "laws")) check(!view.has(field), "不提供作者隐藏目录：" + field);
                    return base.run(context);
                }
            });
            ObjectNode result = advance(f, engine, "relationship-priority"); action(result, "ask", "target", "boatman");
            equal(calls.get(), 5, "实际执行五个目标组件");
            equal(knowledge(f.store.view("apprentice"), "cargo-location").path("source").asText(), "boatman", "选择的是熟悉的交接对象");
            check(!result.toString().contains("家中有人生病"), "来源人的私事不会混入这次目标记录");
        });

        run("相同组件可用于新的世界、人物、地点和物品标识", () -> {
            ObjectNode seed = smallSeed(); Fixture f = fresh(seed); ObjectNode template = read("goal-inspect.json");
            template.put("actor", "courier"); ObjectNode goal = (ObjectNode) template.path("scenario").path("goal");
            goal.put("id", "inspect-sealed-parcel").put("factId", "parcel-site").put("locationFactId", "parcel-site").put("label", "查明封缄包裹存放处");
            ExperienceEngine engine = new ExperienceEngine(); String[] modules = {"ask", "travel", "observe"};
            for (int i = 0; i < modules.length; i++) {
                ObjectNode input = template.deepCopy(); identity(input, f, "new-object-" + i);
                ObjectNode result = engine.run(f.store, input, next("new-object")); equal(result.path("status").asText(), "completed", "新标识也能正常执行");
                equal(stage(result, Stage.RESOLVE).path("output").path("action").path("module").asText(), modules[i], "不硬编码河港人物或货物");
            }
            equal(f.store.view("courier").path("self").path("place").asText(), "parcel-room", "新地点真实移动");
            equal(knowledge(f.store.view("courier"), "parcel-site").path("source").asText(), "@world", "新物品取得实际观察认识");
        });

        run("观察是否允许仍由内核决定，已到传闻地点也不能伪造观察成功", () -> {
            ObjectNode seed = seed(); person(seed, "apprentice").put("place", "warehouse");
            ((ArrayNode) fact(seed, "cargo-location").path("knownBy")).add("apprentice");
            fact(seed, "cargo-location").putArray("observableAt").add("forge"); Fixture f = fresh(seed);
            ObjectNode input = request(f, "not-observable-here"); Path path = next("observation-denied"); byte[] bytes = Files.readAllBytes(f.path);
            rejects(() -> new ExperienceEngine().run(f.store, input, path)); JsonNode failure = JSON.readTree(path.toFile());
            action(failure, "observe", "factId", "cargo-location"); check(!failure.has("receipt"), "候选观察不能冒充内核允许观察");
            check(Arrays.equals(bytes, Files.readAllBytes(f.path)), "未获观察许可时世界不变");
            equal(knowledge(f.store.view("apprentice"), "cargo-location").path("source").asText(), "@seed", "不改写成伪 @world 来源");
            equal(f.store.history().path("events").size(), 0, "非法观察没有事件");
        });

        run("非法目标、人工候选或作者身份不能进入目标动作提交", () -> {
            Fixture f = fresh(); List<ObjectNode> invalid = new ArrayList<>();
            ObjectNode wrongKind = request(f, "invalid-goal-kind"); ((ObjectNode) wrongKind.path("scenario").path("goal")).put("kind", "invent-new-world-truth"); invalid.add(wrongKind);
            ObjectNode missing = request(f, "missing-goal-fact"); ((ObjectNode) missing.path("scenario").path("goal")).remove("factId"); invalid.add(missing);
            ObjectNode manual = request(f, "manual-candidate-injection"); ((ObjectNode) manual.path("scenario")).putArray("candidates"); invalid.add(manual);
            ObjectNode author = request(f, "author-goal-spoof"); author.put("actor", "@author"); invalid.add(author);
            byte[] bytes = Files.readAllBytes(f.path);
            for (ObjectNode input : invalid) { rejects(() -> new ExperienceEngine().run(f.store, input, next("invalid-goal"))); check(Arrays.equals(bytes, Files.readAllBytes(f.path)), "非法目标不改世界"); }
            ObjectNode observe = actionInput(f, "author-observe"); observe.put("actor", "@author").put("factId", "cargo-location");
            rejects(() -> f.store.step("observe", observe)); check(Arrays.equals(bytes, Files.readAllBytes(f.path)), "观察模块不能借作者身份绕过人物视图");
        });

        run("非法上下文与路线结构必须拒绝，不能静默变成动作依据", () -> {
            Fixture f = fresh(); ObjectNode input = request(f, "invalid-context"); ExperienceComponent prepare = goalComponent(Stage.PREPARE);
            for (String variation : List.of("world", "revision", "self", "actor")) {
                ObjectNode view = (ObjectNode) f.store.view("apprentice");
                if (variation.equals("world")) view.put("worldId", "another-world");
                if (variation.equals("revision")) view.put("revision", 9);
                if (variation.equals("self")) ((ObjectNode) view.path("self")).put("id", "boatman");
                if (variation.equals("actor")) view.put("actor", "boatman");
                rejects(() -> prepare.run(new ExperienceComponent.Context(input, view, JSON.createObjectNode(), null)));
            }
            for (String variation : List.of("object-not-array", "missing-end", "numeric-evidence")) {
                ObjectNode seed = seed(); ((ArrayNode) fact(seed, "cargo-location").path("knownBy")).add("apprentice");
                ObjectNode profile = (ObjectNode) person(seed, "apprentice").path("privateProfile");
                if (variation.equals("object-not-array")) profile.putObject("knownRoutes").put("to", "warehouse");
                if (variation.equals("missing-end")) ((ObjectNode) profile.path("knownRoutes").get(0)).remove("to");
                if (variation.equals("numeric-evidence")) ((ObjectNode) profile.path("knownRoutes").get(0)).put("evidenceFactId", 5);
                Fixture malformed = fresh(seed); byte[] before = Files.readAllBytes(malformed.path);
                rejects(() -> new ExperienceEngine().run(malformed.store, request(malformed, "bad-route-" + variation), next("malformed-route")));
                check(Arrays.equals(before, Files.readAllBytes(malformed.path)), "路线结构非法时不移动");
            }
        });

        run("选择、行动和反馈阶段不能用 satisfied 绕过各自结果合同", () -> {
            for (Stage illegal : List.of(Stage.SELECT, Stage.RESOLVE, Stage.FEEDBACK)) {
                Fixture f = fresh(); ExperienceEngine engine = new ExperienceEngine(); String id = "illegal-satisfied-" + illegal.key();
                engine.register(new ExperienceComponent() {
                    public String id() { return id; }
                    public String version() { return "1"; }
                    public Stage stage() { return illegal; }
                    public ObjectNode run(Context context) { return JSON.createObjectNode().put("status", "satisfied").put("reason", "此阶段不能提前满足"); }
                });
                ObjectNode input = request(f, id); configure(input, illegal, id); Path path = next("illegal-satisfied");
                rejects(() -> engine.run(f.store, input, path)); JsonNode result = JSON.readTree(path.toFile());
                check(!result.path("status").asText().equals("satisfied"), "非法阶段不能产生正常满足终态");
                equal(f.store.history().path("events").size(), illegal == Stage.FEEDBACK ? 1 : 0, "只有真实反馈前动作可能已经提交");
                equal(result.path("status").asText(), illegal == Stage.FEEDBACK ? "feedbackFailed" : "failed", "失败状态区分是否已提交");
            }
        });

        run("有限推进预算暂停可续跑，完成后全程重读不新增事件", () -> {
            Fixture f = fresh(); ObjectNode original = request(f, "journey-resumable"); Path directory = testRoot.resolve("journey-resumable");
            ObjectNode paused = GoalJourney.run(f.store, original, directory, 2);
            equal(paused.path("status").asText(), "paused", "两步预算结束时保留未完成目标");
            equal(f.store.inspect().path("revision").asInt(), 2, "仅完成询问与移动两个真实事件");
            equal(position(f, "apprentice"), "warehouse", "暂停处于下一次可观察地点");
            equal(knowledge(f.store.view("apprentice"), "cargo-location").path("source").asText(), "boatman", "预算耗尽不能补写现场观察");
            byte[] first = Files.readAllBytes(directory.resolve("step-001.json")), second = Files.readAllBytes(directory.resolve("step-002.json"));
            ObjectNode satisfied = GoalJourney.run(f.store, original.deepCopy(), directory, 8);
            equal(satisfied.path("status").asText(), "satisfied", "增加预算后继续同一目标到正常满足");
            equal(f.store.inspect().path("revision").asInt(), 3, "续跑只追加一次观察，不重复前两个事件");
            equal(knowledge(f.store.view("apprentice"), "cargo-location").path("source").asText(), "@world", "续跑由实际观察完成目标");
            check(Arrays.equals(first, Files.readAllBytes(directory.resolve("step-001.json")))
                && Arrays.equals(second, Files.readAllBytes(directory.resolve("step-002.json"))), "已完成步骤记录保持原字节");
            byte[] world = Files.readAllBytes(f.path); Map<String, byte[]> completeFiles = snapshot(directory);
            equal(GoalJourney.run(f.store, original, directory, 8), satisfied, "完成旅程重读原结果");
            check(Arrays.equals(world, Files.readAllBytes(f.path)), "完成旅程重读不增加世界事件");
            equalFiles(completeFiles, snapshot(directory), "完成旅程不暗改已保存步骤");
        });

        run("同一推进目录不能换完整输入，原世界和步骤文件保持不变", () -> {
            Fixture f = fresh(); ObjectNode original = request(f, "journey-bound-input"); Path directory = testRoot.resolve("journey-bound-input");
            GoalJourney.run(f.store, original, directory, 2); byte[] world = Files.readAllBytes(f.path); Map<String, byte[]> files = snapshot(directory);
            ObjectNode changed = original.deepCopy(); changed.put("intent", "改成别的目标意图");
            rejects(() -> GoalJourney.run(f.store, changed, directory, 8));
            check(Arrays.equals(world, Files.readAllBytes(f.path)), "不同完整输入不改变原旅程世界");
            equalFiles(files, snapshot(directory), "不同输入不得覆盖或增加旧旅程文件");
        });

        run("有限推进发现路线未知时停止，只保留已成功询问事件", () -> {
            ObjectNode seed = seed(); ((ObjectNode) person(seed, "apprentice").path("privateProfile")).remove("knownRoutes"); Fixture f = fresh(seed);
            Path directory = testRoot.resolve("journey-needs-route"); ObjectNode result = GoalJourney.run(f.store, request(f, "journey-needs-route"), directory, 8);
            equal(result.path("status").asText(), "needsInput", "缺人物路线认识时不能自动继续");
            equal(f.store.history().path("events").size(), 1, "只已有询问事件，未移动或观察");
            equal(position(f, "apprentice"), "wharf", "不能使用真实世界道路填补人物缺口");
            equal(knowledge(f.store.view("apprentice"), "cargo-location").path("source").asText(), "boatman", "仍然只是来源人消息");
            JsonNode stopped = JSON.readTree(directory.resolve("step-002.json").toFile());
            equal(stopped.path("status").asText(), "needsInput", "第二步保存真实路线缺口");
            check(!stopped.has("receipt") && !stopped.has("prepared") && !Files.exists(directory.resolve("step-003.json")), "停止时不产生额外动作或下一步");
        });

        long passed = RESULTS.stream().filter(row -> row.path("passed").asBoolean()).count(); ObjectNode report = JSON.createObjectNode();
        report.put("checks", RESULTS.size()).put("passed", passed).put("failed", RESULTS.size() - passed).set("results", JSON.valueToTree(RESULTS));
        report.put("actualModelCalled", false).put("formalServiceUsed", false).put("proseQualityEvaluated", false);
        report.put("evidenceLimit", "仅验证人物局部认识、显式目标的一步候选、已知路径和正式事务；不证明模型心理推导、复杂物理或小说质量。");
        report.put("isolatedTestDirectory", testRoot.toString()).put("examplesDirectory", examples.toAbsolutePath().toString());
        if (args.length > 0) { Path output = Path.of(args[0]).toAbsolutePath(); Files.createDirectories(output.getParent()); JSON.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report); }
        System.out.println(JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report)); if (passed != RESULTS.size()) System.exit(1);
    }

    private record Fixture(Path path, WorldStore store) {}
    private static Path next(String kind) { return testRoot.resolve(kind + "-" + ++sequence + ".json"); }
    private static ObjectNode read(String name) throws Exception { return (ObjectNode) JSON.readTree(examples.resolve(name).toFile()); }
    private static ObjectNode seed() throws Exception { return (ObjectNode) new WorldGenesis().build(read("goal-river-town.json")).path("seed"); }
    private static Fixture fresh() throws Exception { return fresh(seed()); }
    private static Fixture fresh(ObjectNode seed) throws Exception { Path path = next("world"); WorldStore store = new WorldStore(path); store.initialize(seed); return new Fixture(path, store); }
    private static ObjectNode request(Fixture f, String id) throws Exception { ObjectNode result = read("goal-inspect.json"); identity(result, f, id); return result; }
    private static void identity(ObjectNode result, Fixture f, String id) throws Exception { result.put("worldId", f.store.inspect().path("worldId").asText()).put("expectedRevision", f.store.inspect().path("revision").asInt()).put("requestId", id); }
    private static ObjectNode advance(Fixture f, ExperienceEngine engine, String id) throws Exception { return engine.run(f.store, request(f, id), next("goal")); }
    private static void configure(ObjectNode request, Stage stage, String id) { for (JsonNode row : request.path("components")) if (row.path("stage").asText().equals(stage.key())) { ((ObjectNode) row).put("id", id); return; } throw new AssertionError("缺少阶段"); }
    private static ExperienceComponent goalComponent(Stage stage) { for (ExperienceComponent component : KnowledgeGoalComponents.all()) if (component.stage() == stage) return component; throw new AssertionError("缺少目标组件"); }
    private static JsonNode stage(JsonNode record, Stage stage) { for (JsonNode row : record.path("stages")) if (row.path("stage").asText().equals(stage.key()) && row.path("status").asText().equals("completed")) return row; throw new AssertionError("缺少完成阶段：" + stage); }
    private static void action(JsonNode record, String module, String key, String value) { JsonNode action = stage(record, Stage.RESOLVE).path("output").path("action"); equal(action.path("module").asText(), module, "实际选出的动作"); equal(action.path("arguments").path(key).asText(), value, "实际动作参数"); }
    private static ObjectNode person(ObjectNode seed, String id) { return byId(seed.path("characters"), id); }
    private static ObjectNode fact(ObjectNode seed, String id) { return byId(seed.path("facts"), id); }
    private static ObjectNode byId(JsonNode values, String id) { for (JsonNode row : values) if (row.path("id").asText().equals(id)) return (ObjectNode) row; throw new AssertionError("未找到：" + id); }
    private static JsonNode optionalKnowledge(JsonNode view, String factId) { for (JsonNode row : view.path("knowledge")) if (row.path("factId").asText().equals(factId)) return row; return null; }
    private static JsonNode knowledge(JsonNode view, String factId) { JsonNode value = optionalKnowledge(view, factId); if (value == null) throw new AssertionError("没有对应认识：" + factId); return value; }
    private static String position(Fixture f, String personId) throws Exception { return f.store.view(personId).path("self").path("place").asText(); }
    private static void addPerson(ObjectNode seed, String id, String name, String place) { ObjectNode person = ((ArrayNode) seed.path("characters")).addObject().put("id", id).put("name", name).put("place", place); person.putObject("publicProfile"); person.putObject("privateProfile"); }
    private static void addKnownRoute(ObjectNode seed, String from, String to, String factId, boolean value, boolean known) {
        ObjectNode fact = ((ArrayNode) seed.path("facts")).addObject().put("id", factId).put("subject", "path-" + from + "-" + to).put("predicate", "traversable").put("value", value).put("mode", "fixed");
        ArrayNode knownBy = fact.putArray("knownBy"); if (known) knownBy.add("apprentice");
        ((ArrayNode) person(seed, "apprentice").path("privateProfile").path("knownRoutes")).addObject().put("from", from).put("to", to).put("evidenceFactId", factId);
    }
    private static ObjectNode actionInput(Fixture f, String id) throws Exception { return JSON.createObjectNode().put("schemaVersion", 1).put("worldId", f.store.inspect().path("worldId").asText()).put("requestId", id).put("expectedRevision", f.store.inspect().path("revision").asInt()).put("actor", "apprentice"); }
    private static ObjectNode authorProposal(Fixture f, String id, ObjectNode effect) throws Exception { ObjectNode proposal = actionInput(f, id); proposal.put("actor", "@author").put("reason", "测试中的向前真实位置变化"); proposal.putArray("effects").add(effect); return proposal; }
    private static Map<String, byte[]> snapshot(Path directory) throws Exception {
        Map<String, byte[]> result = new LinkedHashMap<>();
        try (Stream<Path> files = Files.walk(directory)) {
            for (Path file : files.filter(Files::isRegularFile).sorted().toList()) result.put(directory.relativize(file).toString(), Files.readAllBytes(file));
        }
        return result;
    }
    private static void equalFiles(Map<String, byte[]> expected, Map<String, byte[]> actual, String why) {
        equal(actual.keySet(), expected.keySet(), why + "：文件集合");
        for (String path : expected.keySet()) check(Arrays.equals(expected.get(path), actual.get(path)), why + "：" + path);
    }
    private static ObjectNode smallSeed() {
        ObjectNode seed = JSON.createObjectNode().put("schemaVersion", 1).put("worldId", "parcel-world").put("title", "封缄包裹的新局部世界");
        ArrayNode places = seed.putArray("places"); places.addObject().put("id", "station").put("name", "驿站"); places.addObject().put("id", "parcel-room").put("name", "存件间");
        seed.putArray("characters"); addPerson(seed, "courier", "阿递", "station"); addPerson(seed, "clerk", "小吏", "station");
        ObjectNode profile = (ObjectNode) person(seed, "courier").path("privateProfile"); profile.put("intent", "核实包裹存放处");
        profile.putArray("knownRoutes").addObject().put("from", "station").put("to", "parcel-room").put("evidenceFactId", "station-room-path");
        seed.putArray("routes").addObject().put("from", "station").put("to", "parcel-room");
        ArrayNode facts = seed.putArray("facts"); ObjectNode location = facts.addObject().put("id", "parcel-site").put("subject", "sealed-parcel").put("predicate", "stored-at").put("value", "parcel-room").put("mode", "state");
        location.putArray("knownBy").add("clerk"); location.putArray("observableAt").add("parcel-room");
        facts.addObject().put("id", "station-room-path").put("subject", "station-room").put("predicate", "traversable").put("value", true).put("mode", "fixed").putArray("knownBy").add("courier");
        seed.putArray("relationships").addObject().put("from", "courier").put("to", "clerk").put("value", "平日交接包裹"); return seed;
    }
    private static void rejects(Checked action) throws Exception { try { action.run(); } catch (IllegalArgumentException | IllegalStateException | java.io.IOException expected) { return; } throw new AssertionError("应拒绝的操作被接受"); }
    private static void run(String name, Checked action) { ObjectNode result = JSON.createObjectNode().put("name", name); try { action.run(); result.put("passed", true); } catch (Exception | AssertionError failure) { result.put("passed", false).put("error", failure.getClass().getSimpleName() + ": " + failure.getMessage()); } RESULTS.add(result); }
    private static void equal(Object actual, Object expected, String why) { boolean same = actual instanceof JsonNode a && expected instanceof JsonNode b ? Protocol.canonical(a).equals(Protocol.canonical(b)) : java.util.Objects.equals(actual, expected); check(same, why + "；预期=" + expected + "；实际=" + actual); }
    private static void check(boolean condition, String why) { if (!condition) throw new AssertionError(why); }
    @FunctionalInterface private interface Checked { void run() throws Exception; }
}
