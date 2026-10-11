package creativeworld;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** 显式准备条件与知情边界的只读检查，不评估小说质量或推断未声明条件。 */
public final class PreparationCheck {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<ObjectNode> RESULTS = new ArrayList<>();
    private static Path testRoot, examples;
    private static int fixtureNumber;

    public static void main(String[] args) throws Exception {
        testRoot = Files.createTempDirectory("creative-world-preparation-check-");
        examples = args.length > 1 ? Path.of(args[1]) : Path.of("examples");
        if (args.length < 2 && !Files.exists(examples.resolve("genesis-river-town.json")))
            examples = Path.of("creative-world-engine", "examples");

        run("阿禾定位未知与取货资料缺失分开，数量40不能代替重量", () -> {
            Fixture f = fresh(seed(false, false)); ObjectNode request = pickupRequest(f);
            JsonNode report = analyze(f, request);
            status(report, "authorReport", "location", "actorUnknown");
            for (String id : List.of("owner", "permission", "weight", "tool"))
                status(report, "authorReport", id, "worldMissing");
            status(report, "authorReport", "quantity", "ready");
            equal(byId(report.path("authorReport").path("requirements"), "location").path("worldEvidence").path("value").asText(), "warehouse", "作者可核对实际隐藏位置");
            equal(byId(report.path("authorReport").path("requirements"), "quantity").path("worldEvidence").path("value").asInt(), 40, "作者证据确为计数而非重量");
            stage(report, "authorReport", "locate", "needsInput");
            stage(report, "authorReport", "pickup", "needsInput");
            equal(report.path("authorReport").path("stages").get(0).path("stage").asText(), "locate", "先定位的声明阶段在前");
            equal(report.path("authorReport").path("stages").get(1).path("stage").asText(), "pickup", "取货声明阶段随后");
            equal(report.path("authorReport").path("status").asText(), "needsInput", "声明准备尚有缺口");
            for (String id : List.of("location", "owner", "permission", "weight", "tool"))
                status(report, "actorReport", id, "unknown");
            equal(report.path("actorReport").path("requirements").size(), 5, "人物报告只保留actor范围条目");
            equal(report.path("analyzer").path("id").asText(), "world-preparation", "分析器身份");
            equal(report.path("analyzer").path("version").asText(), "1", "分析器版本");
            equal(report.path("template").path("id").asText(), "cargo-handoff", "声明模板身份");
            equal(report.path("template").path("version").asText(), "1", "声明模板版本");
            for (String audience : List.of("authorReport", "actorReport"))
                equal(report.path(audience).path("checksScope").asText(), "declared-requirements-only", "就绪仅限声明检查");
        });

        run("人物报告不暴露世界真值和他人私事，存在但未知与不存在均unknown", () -> {
            Fixture f = fresh(seed(false, false)); ObjectNode request = request(f, "先问清货物相关资料");
            requirements(request).add(requirement("location", "货物在哪里", "locate", "cargo-location", "actor"));
            requirements(request).add(requirement("hidden", "货物标记", "locate", "private-mark", "actor"));
            requirements(request).add(requirement("missing", "尚未声明的标记", "locate", "absent-mark", "actor"));
            JsonNode report = analyze(f, request), actor = report.path("actorReport");
            for (String id : List.of("location", "hidden", "missing")) status(report, "actorReport", id, "unknown");
            status(report, "authorReport", "hidden", "actorUnknown"); status(report, "authorReport", "missing", "worldMissing");
            check(!containsField(actor, "worldEvidence"), "人物报告不能携带世界证据字段");
            for (String hidden : List.of("warehouse", "未公开-货主暗记-573", "陈舟家中病情-842", "铁匠私事-931"))
                check(!actor.toString().contains(hidden), "人物报告不能暴露隐情：" + hidden);
            for (JsonNode row : actor.path("requirements"))
                check(!row.has("actorEvidence"), "从未取得的认识不能伪造证据");
            JsonNode hidden = byId(actor.path("requirements"), "hidden"), missing = byId(actor.path("requirements"), "missing");
            equal(hidden.path("reason"), missing.path("reason"), "未知原因不透露隐藏事实是否存在");
            equal(hidden.path("action"), missing.path("action"), "未知补救说明不透露隐藏事实是否存在");
        });

        run("问陈舟取得定位认识后定位就绪，但不会自动补齐取货条件", () -> {
            Fixture f = fresh(seed(false, false)); ObjectNode request = pickupRequest(f);
            f.store.step("ask", action(f, "ask-location").put("target", "boatman").put("factId", "cargo-location"));
            JsonNode report = analyze(f, request);
            status(report, "authorReport", "location", "ready"); status(report, "actorReport", "location", "ready");
            stage(report, "authorReport", "locate", "ready"); stage(report, "actorReport", "locate", "ready");
            stage(report, "authorReport", "pickup", "needsInput"); stage(report, "actorReport", "pickup", "needsInput");
            JsonNode known = knowledge(f.store.view("apprentice"), "cargo-location");
            equal(known.path("source").asText(), "boatman", "定位来自陈舟实际转述");
            equal(byId(report.path("actorReport").path("requirements"), "location").path("actorEvidence"), known, "人物报告给出本次版本真实认识证据");
            equal(f.store.history().path("events").size(), 1, "分析没有冒充第二次行动");
        });

        run("补齐明确事实后声明条件就绪，仍未移动人物或取得货物", () -> {
            Fixture f = fresh(seed(false, false)); ObjectNode request = pickupRequest(f);
            f.store.step("ask", action(f, "known-location").put("target", "boatman").put("factId", "cargo-location"));
            f.store.apply(proposal(f, "declare-handoff", define(fact("cargo-owner", "cargo", "owner", JSON.valueToTree("smith"), "apprentice")),
                define(fact("cargo-permission", "cargo", "permission", JSON.valueToTree(true), "apprentice")),
                define(fact("cargo-weight", "cargo", "weight", JSON.valueToTree(80), "apprentice")),
                define(fact("cargo-tool", "cargo", "tool", JSON.valueToTree("handcart"), "apprentice"))));
            JsonNode report = analyze(f, request);
            for (String audience : List.of("authorReport", "actorReport")) {
                equal(report.path(audience).path("status").asText(), "ready", "仅声明条件全部满足：" + audience);
                stage(report, audience, "locate", "ready"); stage(report, audience, "pickup", "ready");
            }
            equal(f.store.view("apprentice").path("self").path("place").asText(), "wharf", "就绪不等于已出发");
            equal(byId(f.store.view("@author").path("facts"), "cargo-location").path("value").asText(), "warehouse", "就绪不等于已取得货物");
            equal(f.store.history().path("events").size(), 2, "只有询问与作者明确补充形成事件");
        });

        run("许可false不默认放行，false与0有值而null和空白仍未知", () -> {
            ObjectNode seed = seed(false, false); ArrayNode facts = (ArrayNode) seed.path("facts");
            facts.add(fact("cargo-permission", "cargo", "permission", JSON.valueToTree(false), "apprentice"));
            facts.add(fact("zero-cost", "cargo", "cost", JSON.valueToTree(0), "apprentice"));
            facts.add(fact("false-flag", "cargo", "flag", JSON.valueToTree(false), "apprentice"));
            facts.add(fact("null-data", "cargo", "null-data", JSON.nullNode(), "apprentice"));
            facts.add(fact("blank-data", "cargo", "blank-data", JSON.valueToTree(" \t"), "apprentice"));
            Fixture f = fresh(seed); ObjectNode request = request(f, "检查声明的许可和资料");
            requirements(request).add(equals(requirement("permission", "允许取货", "pickup", "cargo-permission", "actor"), JSON.valueToTree(true)));
            requirements(request).add(equals(requirement("zero", "零费用", "pickup", "zero-cost", "actor"), JSON.valueToTree(0)));
            requirements(request).add(requirement("false", "已确认否定", "pickup", "false-flag", "actor"));
            requirements(request).add(requirement("null", "未填写资料", "pickup", "null-data", "actor"));
            requirements(request).add(requirement("blank", "空白资料", "pickup", "blank-data", "actor"));
            JsonNode report = analyze(f, request);
            for (String audience : List.of("authorReport", "actorReport")) {
                status(report, audience, "permission", "conditionUnmet");
                status(report, audience, "zero", "ready"); status(report, audience, "false", "ready");
                status(report, audience, "null", "valueUnknown"); status(report, audience, "blank", "valueUnknown");
                stage(report, audience, "pickup", "needsInput");
            }
        });

        run("世界位置改变不会偷偷更新已有认识，作者提示认识冲突而人物any仍按自身认识判断", () -> {
            Fixture f = fresh(seed(false, true)); ObjectNode request = request(f, "按已有消息检查位置");
            requirements(request).add(equals(requirement("location", "已听说在货栈", "locate", "cargo-location", "actor"), JSON.valueToTree("warehouse")));
            f.store.apply(proposal(f, "cargo-changed", change("cargo-location", JSON.valueToTree("warehouse"), JSON.valueToTree("forge"))));
            JsonNode report = analyze(f, request);
            status(report, "authorReport", "location", "knowledgeConflict");
            status(report, "actorReport", "location", "ready"); stage(report, "actorReport", "locate", "ready");
            equal(knowledge(f.store.view("apprentice"), "cargo-location").path("value").asText(), "warehouse", "旧认识未改写真值");
            check(!report.path("actorReport").toString().contains("forge"), "人物报告不能从作者真值知道新位置");
        });

        run("currentRevision要求本版本直接观察，种子与本版转述不冒充重新核实", () -> {
            ObjectNode seed = seed(true, true); ((ObjectNode) byId(seed.path("characters"), "apprentice")).put("place", "warehouse");
            Fixture f = fresh(seed); ObjectNode request = request(f, "本次重新核实位置");
            requirements(request).add(freshness(requirement("location", "本次位置依据", "locate", "cargo-location", "actor"), "currentRevision"));
            status(analyze(f, request), "actorReport", "location", "needsRecheck");
            f.store.step("observe", action(f, "observe-initial-location").put("factId", "cargo-location"));
            status(analyze(f, request), "actorReport", "location", "ready");
            f.store.apply(proposal(f, "same-year-change", change("cargo-location", JSON.valueToTree("warehouse"), JSON.valueToTree("forge"))));
            JsonNode report = analyze(f, request); status(report, "actorReport", "location", "needsRecheck");
            stage(report, "actorReport", "locate", "needsInput");
            equal(report.path("source").path("worldTime").path("year").asInt(), 100, "同年变化未冒充年份推进");
            equal(report.path("source").path("revision").asInt(), 2, "事实变化确已推进版本");
            equal(knowledge(f.store.view("apprentice"), "cargo-location").path("revision").asInt(), 1, "旧直接观察仍保持原版本");

            Fixture transferred = fresh(seed(true, false)); ObjectNode transferredRequest = request(transferred, "核对转述是否已直接验证");
            requirements(transferredRequest).add(freshness(requirement("location", "本次位置依据", "locate", "cargo-location", "actor"), "currentRevision"));
            transferred.store.apply(proposal(transferred, "world-location-changed", change("cargo-location", JSON.valueToTree("warehouse"), JSON.valueToTree("forge"))));
            transferred.store.step("ask", action(transferred, "ask-old-boatman").put("target", "boatman").put("factId", "cargo-location"));
            JsonNode old = knowledge(transferred.store.view("apprentice"), "cargo-location");
            equal(old.path("revision").asInt(), 2, "获取旧转述发生在当前版本");
            equal(old.path("value").asText(), "warehouse", "转述没有自动取得世界新值");
            JsonNode currentTransfer = analyze(transferred, transferredRequest);
            status(currentTransfer, "authorReport", "location", "knowledgeConflict");
            status(currentTransfer, "actorReport", "location", "needsRecheck");
            transferred.store.step("travel", action(transferred, "travel-to-observation").put("to", "warehouse"));
            transferred.store.step("observe", action(transferred, "observe-current-location").put("factId", "cargo-location"));
            JsonNode observed = analyze(transferred, transferredRequest);
            status(observed, "authorReport", "location", "ready"); status(observed, "actorReport", "location", "ready");
            equal(knowledge(transferred.store.view("apprentice"), "cargo-location").path("value").asText(), "forge", "只有直接观察取得当前取值");
            equal(knowledge(transferred.store.view("apprentice"), "cargo-location").path("source").asText(), "@world", "当前版证据明确来自观察");
        });

        run("上一年度的认识不满足声明的本年度要求，世界时间推进不自动补知识", () -> {
            Fixture f = fresh(seed(true, true)); ObjectNode request = request(f, "核对今年的位置");
            requirements(request).add(freshness(requirement("location", "今年位置", "locate", "cargo-location", "actor"), "currentYear"));
            status(analyze(f, request), "actorReport", "location", "ready");
            f.store.apply(proposal(f, "ten-years-forward", effect("time.advance").put("expectedYear", 100).put("toYear", 110)));
            JsonNode report = analyze(f, request);
            status(report, "authorReport", "location", "needsRecheck"); status(report, "actorReport", "location", "needsRecheck");
            equal(report.path("source").path("worldTime").path("year").asInt(), 110, "来源绑定当前年度");
            equal(knowledge(f.store.view("apprentice"), "cargo-location").path("asOfYear").asInt(), 100, "原认识证据年份不变");
        });

        run("无年度时钟时不猜currentYear；scope world与缺省any不改变人物认知", () -> {
            Fixture f = fresh(seed(false, true)); ObjectNode request = request(f, "核对位置和客观数量");
            requirements(request).add(freshness(requirement("year", "今年位置", "locate", "cargo-location", "actor"), "currentYear"));
            requirements(request).add(requirement("any", "已有位置消息", "locate", "cargo-location", "actor"));
            requirements(request).add(equals(requirement("world-only", "世界数量", "stock", "iron-quantity", "world"), JSON.valueToTree(40)));
            JsonNode report = analyze(f, request);
            status(report, "actorReport", "year", "needsRecheck"); status(report, "actorReport", "any", "ready");
            status(report, "authorReport", "world-only", "ready");
            equal(report.path("actorReport").path("requirements").size(), 2, "world条件不进入人物报告");
            check(!report.path("source").has("worldTime"), "无时钟时不制造默认年份");
            check(!hasKnowledge(f.store.view("apprentice"), "iron-quantity"), "作者检查数量不授予人物知识");
        });

        run("空重复或畸形声明及错误身份拒绝且保持存档和请求不变", () -> {
            Fixture f = fresh(seed(false, false)); ObjectNode valid = request(f, "检查明确资料");
            requirements(valid).add(requirement("location", "位置", "locate", "cargo-location", "actor"));
            List<ObjectNode> invalid = new ArrayList<>();
            ObjectNode row = valid.deepCopy(); row.putArray("requirements"); invalid.add(row);
            row = valid.deepCopy(); requirements(row).add(requirements(row).get(0).deepCopy()); invalid.add(row);
            row = valid.deepCopy(); ((ObjectNode) requirements(row).get(0)).remove("factId"); invalid.add(row);
            row = valid.deepCopy(); ((ObjectNode) requirements(row).get(0)).put("scope", "reader"); invalid.add(row);
            row = valid.deepCopy(); ((ObjectNode) requirements(row).get(0)).put("freshness", "latest"); invalid.add(row);
            row = valid.deepCopy(); ((ObjectNode) requirements(row).get(0)).put("scope", "world").put("freshness", "currentRevision"); invalid.add(row);
            row = valid.deepCopy(); ((ObjectNode) requirements(row).get(0)).put("scope", "world").put("freshness", "any"); invalid.add(row);
            row = valid.deepCopy(); ((ObjectNode) requirements(row).get(0)).putNull("equals"); invalid.add(row);
            row = valid.deepCopy(); ((ObjectNode) requirements(row).get(0)).putObject("equals").put("value", 1); invalid.add(row);
            row = valid.deepCopy(); ((ObjectNode) requirements(row).get(0)).set("equals", JSON.getNodeFactory().numberNode(Double.NaN)); invalid.add(row);
            row = valid.deepCopy(); row.put("worldId", "another-world"); invalid.add(row);
            row = valid.deepCopy(); row.put("actor", "missing-person"); invalid.add(row);
            row = valid.deepCopy(); row.put("actor", "@author"); invalid.add(row);
            row = valid.deepCopy(); row.put("schemaVersion", 2); invalid.add(row);
            row = valid.deepCopy(); row.put("templateVersion", 1); invalid.add(row);
            row = valid.deepCopy(); row.put("intent", " "); invalid.add(row);
            row = valid.deepCopy(); row.put("autoAct", true); invalid.add(row);
            for (ObjectNode bad : invalid) rejectReadOnly(f, bad);
        });

        run("草案先完整确认才分析，未解决项只给作者且不自动否定已声明条件", () -> {
            ObjectNode blueprint = (ObjectNode) JSON.readTree(examples.resolve("genesis-river-town.json").toFile());
            WorldGenesis genesis = new WorldGenesis(); ObjectNode draft = genesis.build(blueprint), original = draft.deepCopy();
            ObjectNode request = request(draft.path("seed").path("worldId").asText(), "apprentice", "检查客观铁料数量");
            requirements(request).add(equals(requirement("quantity", "铁料计数", "stock", "iron-quantity", "world"), JSON.valueToTree(40)));
            ObjectNode input = request.deepCopy(); List<String> files = files();
            JsonNode report = new WorldPreparation().analyzeDraft(genesis, draft, request);
            equal(report.path("authorReport").path("status").asText(), "ready", "未声明缺口不伪装成自动分析");
            equal(report.path("authorReport").path("unresolved"), blueprint.path("unresolved"), "保留作者声明的未解决项");
            check(!report.path("actorReport").has("unresolved"), "作者未解决项不进入人物报告");
            equal(report.path("source").path("revision").asInt(), 0, "草案没有虚构事件版本");
            equal(report.path("source").path("kind").asText(), "draft", "草案来源不伪装成正式世界");
            equal(draft, original, "草案只读"); equal(request, input, "草案分析请求只读"); equal(files(), files, "草案分析不落正式世界");
            ObjectNode altered = draft.deepCopy(); ((ObjectNode) byId(altered.path("seed").path("facts"), "iron-quantity")).put("value", 41);
            ObjectNode frozenAltered = altered.deepCopy();
            rejects(() -> new WorldPreparation().analyzeDraft(genesis, altered, request));
            equal(altered, frozenAltered, "篡改拒绝不偷偷修复调用者草案"); equal(files(), files, "拒绝篡改也不写文件");
            equal(request, input, "草案拒绝也不修改分析请求");
            equal(draft, original, "篡改副本不改变原草案");
        });

        run("同一声明模板可检查另一人物物品和世界，不硬编码阿禾或货物id", () -> {
            ObjectNode seed = seed(false, false); seed.put("worldId", "herb-town");
            for (JsonNode person : seed.path("characters")) {
                ObjectNode value = (ObjectNode) person;
                if (value.path("id").asText().equals("apprentice")) value.put("id", "buyer").put("name", "顾青");
                if (value.path("id").asText().equals("boatman")) value.put("id", "herbalist").put("name", "叶掌柜");
            }
            ObjectNode location = (ObjectNode) byId(seed.path("facts"), "cargo-location");
            location.put("id", "herb-location").put("subject", "herb-basket").put("value", "forge");
            location.putArray("knownBy").add("herbalist");
            for (JsonNode fact : seed.path("facts")) {
                ArrayNode known = (ArrayNode) fact.path("knownBy");
                for (int i = 0; i < known.size(); i++) if (known.get(i).asText().equals("boatman")) known.set(i, JSON.valueToTree("herbalist"));
            }
            Fixture f = fresh(seed); ObjectNode request = request(f.worldId, "buyer", "先问药材放在哪里");
            requirements(request).add(equals(requirement("location", "药材位置", "locate", "herb-location", "actor"), JSON.valueToTree("forge")));
            status(analyze(f, request), "authorReport", "location", "actorUnknown");
            ObjectNode action = JSON.createObjectNode().put("schemaVersion", 1).put("worldId", f.worldId).put("requestId", "ask-herb")
                .put("expectedRevision", 0).put("actor", "buyer").put("target", "herbalist").put("factId", "herb-location");
            f.store.step("ask", action);
            JsonNode report = analyze(f, request); status(report, "actorReport", "location", "ready");
            equal(knowledge(f.store.view("buyer"), "herb-location").path("source").asText(), "herbalist", "另一对象仍有实际信息来源");
            equal(report.path("template").path("id").asText(), "cargo-handoff", "没有修改分析器或模板种类");
        });

        ObjectNode report = JSON.createObjectNode(); long passed = RESULTS.stream().filter(row -> row.path("passed").asBoolean()).count();
        report.put("checks", RESULTS.size()).put("passed", passed).put("failed", RESULTS.size() - passed);
        report.set("results", JSON.valueToTree(RESULTS)); report.put("actualModelCalled", false).put("formalServiceUsed", false);
        report.put("proseQualityEvaluated", false).put("cliEvaluated", false);
        report.put("evidenceLimit", "仅核对明确声明的准备条件、记录支持的认识冲突与声明时效、作者/人物资料隔离和只读性；不推断消息年龄或全部准备缺口、不执行补救、不证明人物心理或小说质量。");
        report.put("isolatedTestDirectory", testRoot.toString()).put("examplesDirectory", examples.toAbsolutePath().toString());
        if (args.length > 0) {
            Path output = Path.of(args[0]).toAbsolutePath(); Files.createDirectories(output.getParent());
            JSON.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);
        }
        System.out.println(JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report));
        if (passed != RESULTS.size()) System.exit(1);
    }

    private record Fixture(Path path, WorldStore store, String worldId) {}
    private static Fixture fresh(ObjectNode seed) throws Exception {
        Path path = testRoot.resolve("world-" + ++fixtureNumber + ".json"); WorldStore store = new WorldStore(path); store.initialize(seed);
        return new Fixture(path, store, seed.path("worldId").asText());
    }
    private static JsonNode analyze(Fixture f, ObjectNode request) throws Exception {
        byte[] bytes = Files.readAllBytes(f.path); JsonNode history = f.store.history().deepCopy(), state = f.store.view("@author").deepCopy();
        ObjectNode original = request.deepCopy(); List<String> files = files();
        JsonNode report = new WorldPreparation().analyze(f.store, request);
        equal(request, original, "分析不改请求");
        check(Arrays.equals(bytes, Files.readAllBytes(f.path)), "分析不改正式文件字节");
        equal(f.store.history(), history, "分析不新增经历"); equal(f.store.view("@author"), state, "分析不改世界资料");
        equal(files(), files, "分析不生成新正式文件");
        equal(report.path("source").path("worldId").asText(), f.worldId, "同一世界来源");
        equal(report.path("source").path("kind").asText(), "world", "正式世界的分析来源");
        equal(report.path("source").path("revision").asInt(), history.path("revision").asInt(), "来源版本绑定本次只读状态");
        check(!containsField(report.path("actorReport"), "worldEvidence"), "全部人物报告禁止世界证据");
        for (String audience : List.of("authorReport", "actorReport")) for (JsonNode item : report.path(audience).path("requirements")) {
            check(item.path("reason").isTextual() && !item.path("reason").asText().isBlank(), "缺口判断要给出具体原因");
            check(item.path("action").isTextual() && !item.path("action").asText().isBlank(), "补救项是说明，不伪装成已执行动作");
        }
        return report;
    }
    private static void rejectReadOnly(Fixture f, ObjectNode request) throws Exception {
        byte[] bytes = Files.readAllBytes(f.path); JsonNode history = f.store.history().deepCopy(); ObjectNode original = request.deepCopy(); List<String> files = files();
        rejects(() -> new WorldPreparation().analyze(f.store, request));
        check(request.equals(original), "拒绝不改请求"); equal(f.store.history(), history, "拒绝不新增事件");
        check(Arrays.equals(bytes, Files.readAllBytes(f.path)), "拒绝不改存档"); equal(files(), files, "拒绝不生成新文件");
    }
    private static ObjectNode seed(boolean timed, boolean actorKnowsLocation) {
        ObjectNode seed = JSON.createObjectNode().put("schemaVersion", 1).put("worldId", "preparation-town").put("title", "准备资料核对小世界");
        seed.putArray("places").add(place("wharf", "河埠")).add(place("warehouse", "货栈")).add(place("forge", "铁坊"));
        seed.putArray("characters").add(person("apprentice", "阿禾", "wharf", "今日只准备问路"))
            .add(person("boatman", "陈舟", "wharf", "陈舟家中病情-842")).add(person("smith", "林桂", "forge", "铁匠私事-931"));
        ObjectNode location = fact("cargo-location", "cargo", "location", JSON.valueToTree("warehouse"), "boatman");
        location.putArray("observableAt").add("warehouse").add("forge");
        if (actorKnowsLocation) ((ArrayNode) location.path("knownBy")).add("apprentice");
        seed.putArray("facts").add(location).add(fact("iron-quantity", "cargo", "quantity", JSON.valueToTree(40), "boatman"))
            .add(fact("private-mark", "cargo", "mark", JSON.valueToTree("未公开-货主暗记-573")));
        if (timed) seed.putObject("worldTime").put("unit", "year").put("year", 100);
        return seed;
    }
    private static ObjectNode place(String id, String name) { return JSON.createObjectNode().put("id", id).put("name", name); }
    private static ObjectNode person(String id, String name, String place, String concern) {
        ObjectNode person = JSON.createObjectNode().put("id", id).put("name", name).put("place", place);
        person.putObject("publicProfile"); person.putObject("privateProfile").put("concern", concern); return person;
    }
    private static ObjectNode fact(String id, String subject, String predicate, JsonNode value, String... knownBy) {
        ObjectNode fact = JSON.createObjectNode().put("id", id).put("subject", subject).put("predicate", predicate).put("mode", "state");
        fact.set("value", value); ArrayNode known = fact.putArray("knownBy"); for (String person : knownBy) known.add(person); return fact;
    }
    private static ObjectNode request(Fixture f, String intent) { return request(f.worldId, "apprentice", intent); }
    private static ObjectNode request(String worldId, String actor, String intent) {
        ObjectNode request = JSON.createObjectNode().put("schemaVersion", 1).put("worldId", worldId).put("actor", actor).put("intent", intent)
            .put("templateId", "cargo-handoff").put("templateVersion", "1"); request.putArray("requirements"); return request;
    }
    private static ObjectNode pickupRequest(Fixture f) {
        ObjectNode request = request(f, "先定位货物，再决定是否和怎样取货"); ArrayNode rows = requirements(request);
        rows.add(requirement("location", "货物位置", "locate", "cargo-location", "actor"));
        rows.add(requirement("owner", "货物所属", "pickup", "cargo-owner", "actor"));
        rows.add(equals(requirement("permission", "允许取货", "pickup", "cargo-permission", "actor"), JSON.valueToTree(true)));
        rows.add(requirement("weight", "货物重量", "pickup", "cargo-weight", "actor"));
        rows.add(requirement("tool", "搬运工具", "pickup", "cargo-tool", "actor"));
        rows.add(equals(requirement("quantity", "铁料计数", "pickup", "iron-quantity", "world"), JSON.valueToTree(40)));
        return request;
    }
    private static ArrayNode requirements(ObjectNode request) { return (ArrayNode) request.path("requirements"); }
    private static ObjectNode requirement(String id, String label, String stage, String factId, String scope) {
        return JSON.createObjectNode().put("id", id).put("label", label).put("stage", stage).put("factId", factId).put("scope", scope);
    }
    private static ObjectNode equals(ObjectNode requirement, JsonNode expected) { requirement.set("equals", expected); return requirement; }
    private static ObjectNode freshness(ObjectNode requirement, String freshness) { return requirement.put("freshness", freshness); }
    private static ObjectNode effect(String type) { return JSON.createObjectNode().put("type", type); }
    private static ObjectNode define(ObjectNode fact) { ObjectNode effect = effect("fact.define"); effect.set("fact", fact); return effect; }
    private static ObjectNode change(String factId, JsonNode expected, JsonNode value) {
        ObjectNode effect = effect("fact.change").put("factId", factId); effect.set("expectedValue", expected); effect.set("value", value); return effect;
    }
    private static ObjectNode proposal(Fixture f, String id, ObjectNode... effects) throws Exception {
        ObjectNode proposal = action(f, id).put("actor", "@author").put("reason", "作者明确补充或更新已确认资料，不推断后续行动");
        ArrayNode rows = proposal.putArray("effects"); for (ObjectNode effect : effects) rows.add(effect); return proposal;
    }
    private static ObjectNode action(Fixture f, String id) throws Exception {
        return JSON.createObjectNode().put("schemaVersion", 1).put("worldId", f.worldId).put("requestId", id)
            .put("expectedRevision", f.store.inspect().path("revision").asInt()).put("actor", "apprentice");
    }
    private static void status(JsonNode report, String audience, String id, String expected) {
        equal(byId(report.path(audience).path("requirements"), id).path("status").asText(), expected, audience + " 条件：" + id);
    }
    private static void stage(JsonNode report, String audience, String name, String expected) {
        for (JsonNode row : report.path(audience).path("stages")) if (row.path("stage").asText().equals(name)) {
            equal(row.path("status").asText(), expected, audience + " 阶段：" + name); return;
        }
        throw new AssertionError("缺少阶段：" + audience + "/" + name);
    }
    private static JsonNode byId(JsonNode rows, String id) {
        for (JsonNode row : rows) if (row.path("id").asText().equals(id)) return row;
        throw new AssertionError("缺少条目：" + id);
    }
    private static JsonNode knowledge(JsonNode view, String id) {
        for (JsonNode row : view.path("knowledge")) if (row.path("factId").asText().equals(id)) return row;
        throw new AssertionError("缺少认识：" + id);
    }
    private static boolean hasKnowledge(JsonNode view, String id) {
        for (JsonNode row : view.path("knowledge")) if (row.path("factId").asText().equals(id)) return true; return false;
    }
    private static boolean containsField(JsonNode node, String field) {
        if (node.isObject() && node.has(field)) return true;
        if (node.isContainerNode()) for (JsonNode child : node) if (containsField(child, field)) return true; return false;
    }
    private static List<String> files() throws Exception {
        try (var values = Files.list(testRoot)) { return values.map(path -> path.getFileName().toString()).sorted().toList(); }
    }
    private static void rejects(Checked action) throws Exception {
        try { action.run(); } catch (IllegalArgumentException | IllegalStateException | java.io.IOException expected) { return; }
        throw new AssertionError("应当拒绝的操作被接受");
    }
    private static void run(String name, Checked action) {
        ObjectNode result = JSON.createObjectNode().put("name", name);
        try { action.run(); result.put("passed", true); }
        catch (Exception | AssertionError failure) { result.put("passed", false).put("error", failure.getClass().getSimpleName() + ": " + failure.getMessage()); }
        RESULTS.add(result);
    }
    private static void equal(Object actual, Object expected, String why) {
        boolean same = actual instanceof JsonNode a && expected instanceof JsonNode b ? Protocol.canonical(a).equals(Protocol.canonical(b))
            : java.util.Objects.equals(actual, expected);
        check(same, why + "；预期=" + expected + "；实际=" + actual);
    }
    private static void check(boolean condition, String why) { if (!condition) throw new AssertionError(why); }
    @FunctionalInterface private interface Checked { void run() throws Exception; }
}
