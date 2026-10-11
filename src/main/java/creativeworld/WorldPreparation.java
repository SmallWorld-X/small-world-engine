package creativeworld;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.io.IOException;
import java.util.*;
import static creativeworld.Protocol.*;

/**
 * 只检查明确声明的准备要求，不补写事实、不推导选择，也不授予提交许可。
 * 作者报告能核对世界真值；人物报告必须独立从本人知识判断。
 * 因而作者可能报告 knowledgeConflict，而人物在 any 政策下仍认为该项 ready，
 * 这种差异保留信息隔离，不能用作者诊断悄悄纠正人物认识。
 */
public final class WorldPreparation {
    public ObjectNode analyze(WorldStore store, JsonNode request) throws IOException {
        ObjectNode frozen = freezeRequest(request);
        Objects.requireNonNull(store, "正式世界不能为空");
        JsonNode metadata = store.inspect();
        checkWorld(frozen, metadata);
        int revision = integer(metadata, "revision");
        // 分别读取同一历史版本，后续并发事件不会让两个报告看到不同的时点。
        JsonNode author = store.viewAt("@author", revision);
        JsonNode actor = store.viewAt(text(frozen, "actor"), revision);
        return report("world", frozen, author, actor, null);
    }

    public ObjectNode analyzeDraft(WorldGenesis genesis, JsonNode draft, JsonNode request) {
        ObjectNode frozen = freezeRequest(request);
        Objects.requireNonNull(genesis, "世界构造器不能为空");
        ObjectNode frozenDraft = canonicalObject(object(draft, "schemaVersion", "kind", "blueprint", "seed", "construction"));
        ObjectNode seed = genesis.confirmSeed(frozenDraft);
        checkWorld(frozen, seed);
        WorldState state = new WorldState(seed);
        return report("draft", frozen, state.view("@author"), state.view(text(frozen, "actor")),
            array(frozenDraft.get("construction"), "unresolved"));
    }

    private static ObjectNode freezeRequest(JsonNode incoming) {
        ObjectNode request = canonicalObject(object(incoming, "schemaVersion", "worldId", "actor", "intent",
            "templateId", "templateVersion", "requirements"));
        version(request); id(request, "worldId"); id(request, "actor"); text(request, "intent");
        id(request, "templateId"); moduleVersion(text(request, "templateVersion"));
        ArrayNode requirements = array(request, "requirements");
        if (requirements.isEmpty()) throw bad("准备分析至少需要一项明确要求");
        Set<String> ids = new HashSet<>();
        for (JsonNode requirement : requirements) {
            object(requirement, Set.of("id", "label", "stage", "factId", "scope"), Set.of("equals", "freshness"));
            if (!ids.add(id(requirement, "id"))) throw bad("准备要求标识重复");
            text(requirement, "label"); text(requirement, "stage"); id(requirement, "factId");
            String scope = text(requirement, "scope");
            if (!Set.of("world", "actor").contains(scope)) throw bad("准备要求scope只支持world或actor");
            if (requirement.has("equals")) {
                JsonNode expected = scalar(requirement.get("equals"), "equals");
                if (expected.isNull()) throw bad("准备条件equals不能为null");
            }
            if (requirement.has("freshness") && !scope.equals("actor"))
                throw bad("时效要求只能用于人物知识");
            if (!Set.of("any", "currentYear", "currentRevision").contains(freshness(requirement)))
                throw bad("准备要求freshness不支持");
        }
        data(request); return request;
    }

    private static void checkWorld(ObjectNode request, JsonNode metadata) {
        if (!text(request, "worldId").equals(text(metadata, "worldId"))) throw bad("准备分析所属世界不一致");
    }

    private static ObjectNode report(String sourceKind, ObjectNode request, JsonNode author, JsonNode actor, ArrayNode unresolved) {
        checkWorld(request, author); checkWorld(request, actor);
        String who = text(request, "actor"); int revision = integer(author, "revision");
        if (!text(actor, "actor").equals(who) || !actor.path("self").path("id").asText().equals(who)
            || integer(actor, "revision") != revision) throw bad("准备分析的人物身份或历史版本不一致");
        if (author.has("worldTime") != actor.has("worldTime")
            || (author.has("worldTime") && !canonical(author.get("worldTime")).equals(canonical(actor.get("worldTime")))))
            throw bad("准备分析的共同时间不一致");

        ObjectNode result = JSON.createObjectNode().put("schemaVersion", 1).put("kind", "world-preparation");
        result.putObject("analyzer").put("id", "world-preparation").put("version", "1");
        result.putObject("template").put("id", text(request, "templateId")).put("version", text(request, "templateVersion"));
        ObjectNode source = result.putObject("source").put("kind", sourceKind)
            .put("worldId", text(request, "worldId")).put("revision", revision);
        if (author.has("worldTime")) source.set("worldTime", author.get("worldTime").deepCopy());
        ObjectNode authorReport = newReport("@author"), actorReport = newReport(who);
        Map<String, JsonNode> facts = index(array(author, "facts"), "id"), known = index(array(actor, "knowledge"), "factId");
        for (JsonNode requirement : array(request, "requirements")) {
            String factId = text(requirement, "factId");
            JsonNode worldFact = facts.get(factId), actorFact = known.get(factId);
            ObjectNode authorItem = item(requirement);
            if (worldFact != null) authorItem.set("worldEvidence", worldFact.deepCopy());
            if (text(requirement, "scope").equals("actor") && actorFact != null)
                authorItem.set("actorEvidence", actorFact.deepCopy());
            apply(authorItem, authorVerdict(requirement, worldFact, actorFact, actor));
            array(authorReport, "requirements").add(authorItem);
            if (text(requirement, "scope").equals("actor")) {
                ObjectNode actorItem = item(requirement);
                if (actorFact != null) actorItem.set("actorEvidence", actorFact.deepCopy());
                apply(actorItem, actorVerdict(requirement, actorFact, actor));
                array(actorReport, "requirements").add(actorItem);
            }
        }
        finish(authorReport); finish(actorReport);
        if (unresolved != null) {
            authorReport.set("unresolved", unresolved.deepCopy());
            array(authorReport, "notes").add("草案未决项仅原样保留，未自动解释、评分或认定解决。");
        }
        result.set("authorReport", authorReport); result.set("actorReport", actorReport);
        return canonicalObject(result);
    }

    private static ObjectNode newReport(String audience) {
        ObjectNode report = JSON.createObjectNode().put("audience", audience).put("checksScope", "declared-requirements-only");
        report.putArray("requirements"); report.putArray("stages");
        report.putArray("notes").add("ready只表示本报告中的声明要求通过，不代表整件事准备完毕、人物选择已推导或获得正式提交许可。")
            .add("action是待补充或复核的说明，本报告不会自动补写事实或执行行动。");
        return report;
    }

    private static ObjectNode item(JsonNode requirement) {
        ObjectNode item = JSON.createObjectNode();
        for (String field : List.of("id", "label", "stage", "factId", "scope")) item.set(field, requirement.get(field).deepCopy());
        return item;
    }

    private static Verdict authorVerdict(JsonNode requirement, JsonNode fact, JsonNode known, JsonNode view) {
        if (fact == null) return verdict("worldMissing", "当前世界资料尚未声明这项事实。", "请由作者明确并确认所需事实；本报告不写入设定。");
        if (unknown(fact.get("value"))) return verdict("valueUnknown", "世界已声明事实，但没有可用取值。", "请由作者确认明确取值。");
        if (text(requirement, "scope").equals("world")) return condition(requirement, fact.get("value"));
        if (known == null) return verdict("actorUnknown", "世界有这项事实，但本人尚无知识快照。", "需要通过有依据的询问或观察取得本人知识。");
        if (unknown(known.get("value"))) return verdict("valueUnknown", "本人知识快照没有可用取值。", "需要取得明确的本人知识依据。");
        if (!canonical(fact.get("value")).equals(canonical(known.get("value"))))
            return verdict("knowledgeConflict", "已记录的本人认识与当前世界事实不一致，未据此推断消息产生时间。", "需要重新核实本人认识，不能直接用作者真值替换。");
        return actorVerdict(requirement, known, view);
    }

    private static Verdict actorVerdict(JsonNode requirement, JsonNode known, JsonNode view) {
        if (known == null) return verdict("unknown", "本人尚无可用的知识依据。", "需要询问或现场核实；未判断隐藏世界是否有这项事实。");
        if (unknown(known.get("value"))) return verdict("valueUnknown", "本人知识快照没有可用取值。", "需要取得明确的本人知识依据。");
        String policy = freshness(requirement);
        if (policy.equals("currentRevision") && (!text(known, "source").equals("@world")
            || integer(known, "revision") != integer(view, "revision")))
            return verdict("needsRecheck", "快照不满足本世界版本的直接观察要求；消息刚取得或转述不代表刚核实。", "需要在本世界版本直接观察并核实这项事实。");
        if (policy.equals("currentYear")) {
            if (!view.has("worldTime")) return verdict("needsRecheck", "世界没有年度时钟，无法核对本次声明的年度要求。", "需要明确时间依据或调整声明要求，不能编造过期结论。");
            if (!known.has("asOfYear") || integer(known, "asOfYear") != WorldTime.from(view.get("worldTime")).year())
                return verdict("needsRecheck", "快照不满足本次声明的当前年度要求；未据此断言事实已经变化。", "需要重新核实这项知识的时效。");
        }
        return condition(requirement, known.get("value"));
    }

    private static Verdict condition(JsonNode requirement, JsonNode actual) {
        if (requirement.has("equals") && !canonical(actual).equals(canonical(requirement.get("equals"))))
            return verdict("conditionUnmet", "可用依据与声明的标量条件不符。", "需要处理未满足的条件，不能把不符值改写成成功。");
        return verdict("ready", "本项声明要求已有可用依据，且满足所声明的条件。", "本声明项暂不需要补充依据。");
    }

    private static String freshness(JsonNode requirement) {
        return requirement.has("freshness") ? text(requirement, "freshness") : "any";
    }
    private static boolean unknown(JsonNode value) {
        return value == null || value.isNull() || (value.isTextual() && value.asText().isBlank());
    }
    private static Map<String, JsonNode> index(ArrayNode values, String field) {
        Map<String, JsonNode> result = new LinkedHashMap<>();
        for (JsonNode value : values) result.put(text(value, field), value);
        return result;
    }
    private record Verdict(String status, String reason, String action) {}
    private static Verdict verdict(String status, String reason, String action) { return new Verdict(status, reason, action); }
    private static void apply(ObjectNode item, Verdict verdict) {
        item.put("status", verdict.status).put("reason", verdict.reason).put("action", verdict.action);
    }
    private static void finish(ObjectNode report) {
        Map<String, Boolean> stages = new LinkedHashMap<>(); boolean ready = true;
        for (JsonNode requirement : array(report, "requirements")) {
            boolean passed = text(requirement, "status").equals("ready"); ready &= passed;
            stages.merge(text(requirement, "stage"), passed, (prior, current) -> prior && current);
        }
        report.put("status", ready ? "ready" : "needsInput");
        ArrayNode summary = array(report, "stages");
        stages.forEach((stage, passed) -> summary.addObject().put("stage", stage).put("status", passed ? "ready" : "needsInput"));
    }
}
