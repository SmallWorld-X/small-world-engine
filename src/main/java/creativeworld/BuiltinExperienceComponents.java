package creativeworld;

import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import static creativeworld.Protocol.*;
import static creativeworld.ExperienceComponent.Stage;

/** 五个无模型调用的声明式组件；优先级是输入策略，不代表人物心理推导。 */
public final class BuiltinExperienceComponents {
    private static final Set<String> IDENTITY = Set.of("schemaVersion", "worldId", "requestId", "expectedRevision", "actor");
    private static final Set<String> CANDIDATE_FIELDS = Set.of("id", "module", "arguments", "description", "priority", "evidence");

    private BuiltinExperienceComponents() {}

    public static List<ExperienceComponent> all() {
        return List.of(new Builtin("declared-preparation", Stage.PREPARE),
            new Builtin("declared-candidates", Stage.CANDIDATES),
            new Builtin("priority-selection", Stage.SELECT),
            new Builtin("selected-action", Stage.RESOLVE),
            new Builtin("actual-feedback", Stage.FEEDBACK));
    }

    private record Builtin(String id, Stage stage) implements ExperienceComponent {
        public String version() { return "1"; }

        public ObjectNode run(Context context) {
            Objects.requireNonNull(context, "演绎上下文不能为空");
            ObjectNode request = context.request(), view = context.actorView(), outputs = context.outputs();
            ObjectNode receipt = context.receipt();
            validateContext(request, view, receipt, stage);
            return switch (stage) {
                case PREPARE -> prepare(request, view);
                case CANDIDATES -> candidates(request, view);
                case SELECT -> select(outputs, view);
                case RESOLVE -> resolve(outputs, view);
                case FEEDBACK -> feedback(request, view, receipt);
            };
        }
    }

    private static void validateContext(ObjectNode request, ObjectNode view, ObjectNode receipt, Stage stage) {
        object(request, "schemaVersion", "worldId", "requestId", "expectedRevision", "actor", "intent", "components", "scenario");
        version(request); id(request, "worldId"); id(request, "requestId"); integer(request, "expectedRevision");
        String actor = id(request, "actor"); text(request, "intent"); data(array(request, "components"));
        object(request.get("scenario"), Set.of(), Set.of("requiredEvidence", "candidates", "remainingGaps"));
        JsonNode scenario = request.get("scenario");
        if (scenario.has("requiredEvidence")) for (JsonNode evidence : array(scenario, "requiredEvidence")) validateEvidence(evidence);
        if (scenario.has("candidates")) validateCandidates(array(scenario, "candidates"));
        if (scenario.has("remainingGaps")) validateGaps(array(scenario, "remainingGaps"));

        version(view);
        if (!text(request, "worldId").equals(text(view, "worldId")) || !actor.equals(text(view, "actor")))
            throw bad("演绎人物视图身份与请求不一致");
        if (!view.path("self").isObject() || !actor.equals(text(view.get("self"), "id")))
            throw bad("人物演绎必须使用本人的人物视图");
        int revision = integer(view, "revision");
        if (stage == Stage.FEEDBACK) {
            object(receipt, "schemaVersion", "worldId", "requestId", "revision", "status"); version(receipt);
            if (!text(receipt, "worldId").equals(text(request, "worldId"))
                || !text(receipt, "requestId").equals(text(request, "requestId"))
                || !text(receipt, "status").equals("committed")) throw bad("反馈必须引用本次已提交回执");
            if (integer(receipt, "revision") <= integer(request, "expectedRevision")
                || revision != integer(receipt, "revision")) throw bad("反馈人物视图版本与提交回执不一致");
        } else {
            if (revision != integer(request, "expectedRevision")) throw bad("人物视图版本与推导起点不一致");
            if (!receipt.isEmpty()) throw bad("提交前的演绎阶段不能携带提交回执");
        }
    }

    private static void validateEvidence(JsonNode evidence) {
        object(evidence, Set.of("path", "label"), Set.of("equals"));
        String path = text(evidence, "path"); text(evidence, "label");
        if (!path.startsWith("/")) throw bad("依据路径必须是人物视图的非空JSON Pointer");
        for (int index = 0; index < path.length(); index++) if (path.charAt(index) == '~') {
            if (++index >= path.length() || (path.charAt(index) != '0' && path.charAt(index) != '1'))
                throw bad("依据路径含无效的JSON Pointer转义");
        }
        JsonPointer.compile(path);
        if (evidence.has("equals")) data(evidence.get("equals"));
    }

    private static void validateCandidates(ArrayNode candidates) {
        Set<String> ids = new HashSet<>();
        for (JsonNode candidate : candidates) {
            object(candidate, CANDIDATE_FIELDS, Set.of("moduleVersion"));
            if (!ids.add(id(candidate, "id"))) throw bad("候选行动标识重复");
            id(candidate, "module"); moduleVersion(candidate); text(candidate, "description"); priority(candidate);
            if (!candidate.path("arguments").isObject()) throw bad("候选行动arguments必须是对象");
            candidate.get("arguments").fieldNames().forEachRemaining(field -> {
                if (IDENTITY.contains(field)) throw bad("候选行动不能覆盖请求身份：" + field);
            });
            data(candidate.get("arguments"));
            for (JsonNode evidence : array(candidate, "evidence")) validateEvidence(evidence);
        }
    }

    private static int priority(JsonNode candidate) {
        JsonNode value = candidate.get("priority");
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) throw bad("候选优先级须为整数");
        return value.intValue();
    }

    private static void validateGaps(ArrayNode gaps) {
        for (JsonNode gap : gaps) {
            if (gap.isTextual()) text(gap, "剩余缺口");
            else if (gap.isObject() && !gap.isEmpty()) data(gap);
            else throw bad("剩余缺口须为非空文本或非空对象");
        }
    }

    private static boolean empty(JsonNode value) {
        return value.isMissingNode() || value.isNull() || (value.isTextual() && value.asText().isBlank())
            || ((value.isObject() || value.isArray()) && value.isEmpty());
    }

    private record EvidenceCheck(boolean available, boolean missing, ArrayNode evidence, ArrayNode gaps) {}

    private static EvidenceCheck checkEvidence(ArrayNode required, ObjectNode view, String candidateId) {
        ArrayNode found = JSON.createArrayNode(), gaps = JSON.createArrayNode();
        boolean missing = false;
        for (JsonNode requirement : required) {
            validateEvidence(requirement);
            String path = text(requirement, "path"), label = text(requirement, "label");
            JsonNode actual = view.at(path);
            String problem = null;
            if (empty(actual)) { missing = true; problem = "人物视图中没有可用依据"; }
            else if (requirement.has("equals") && !canonical(actual).equals(canonical(requirement.get("equals"))))
                problem = "人物可见依据与声明条件不一致";
            if (problem != null) {
                ObjectNode gap = gaps.addObject().put("path", path).put("label", label).put("reason", problem);
                if (candidateId != null) gap.put("candidateId", candidateId);
            } else {
                ObjectNode evidence = found.addObject().put("path", path).put("label", label);
                evidence.set("value", actual.deepCopy());
                if (candidateId != null) evidence.put("candidateId", candidateId);
            }
        }
        return new EvidenceCheck(gaps.isEmpty(), missing, found, gaps);
    }

    private static ArrayNode optionalRows(JsonNode source, String field) {
        return source.has(field) ? array(source, field) : JSON.createArrayNode();
    }

    private static ObjectNode prepare(ObjectNode request, ObjectNode view) {
        EvidenceCheck checked = checkEvidence(optionalRows(request.get("scenario"), "requiredEvidence"), view, null);
        ObjectNode result = JSON.createObjectNode().put("status", checked.available() ? "ready" : "needsInput");
        result.set("evidence", checked.evidence()); result.set("gaps", checked.gaps()); return result;
    }

    private static ObjectNode candidates(ObjectNode request, ObjectNode view) {
        ArrayNode available = JSON.createArrayNode(), evidence = JSON.createArrayNode(), gaps = JSON.createArrayNode();
        boolean missing = false;
        for (JsonNode candidate : optionalRows(request.get("scenario"), "candidates")) {
            EvidenceCheck checked = checkEvidence(array(candidate, "evidence"), view, text(candidate, "id"));
            append(evidence, checked.evidence()); append(gaps, checked.gaps()); missing |= checked.missing();
            if (checked.available()) available.add(candidate.deepCopy());
        }
        ObjectNode result = JSON.createObjectNode().put("status", !available.isEmpty() ? "ready" : missing ? "needsInput" : "blocked");
        result.set("candidates", available); result.set("evidence", evidence); result.set("gaps", gaps);
        if (available.isEmpty()) result.put("reason", "当前没有有依据的可用候选行动；未补造行动或成功结果。");
        return result;
    }

    private static ArrayNode availableCandidates(ObjectNode outputs, ObjectNode view) {
        JsonNode prior = outputs.get(Stage.CANDIDATES.key());
        object(prior, Set.of("status", "candidates"), Set.of("evidence", "gaps", "reason"));
        if (!text(prior, "status").equals("ready")) throw bad("候选阶段尚未就绪，不能继续选择或裁决");
        ArrayNode candidates = array(prior, "candidates"); validateCandidates(candidates);
        for (JsonNode candidate : candidates) if (!checkEvidence(array(candidate, "evidence"), view, text(candidate, "id")).available())
            throw bad("阶段结果包含人物依据不成立的候选行动");
        return candidates;
    }

    private static ObjectNode select(ObjectNode outputs, ObjectNode view) {
        ArrayNode candidates = availableCandidates(outputs, view);
        if (candidates.isEmpty()) return JSON.createObjectNode().put("status", "blocked").put("reason", "没有可选择的候选行动。");
        JsonNode selected = candidates.get(0);
        for (JsonNode candidate : candidates) if (priority(candidate) > priority(selected)) selected = candidate;
        return JSON.createObjectNode().put("status", "ready").put("candidateId", text(selected, "id"))
            .put("reason", "按明确配置的整数优先级选择最高项；同分保留候选顺序。这不是AI心理推导。");
    }

    private static ObjectNode resolve(ObjectNode outputs, ObjectNode view) {
        ArrayNode candidates = availableCandidates(outputs, view);
        JsonNode selection = outputs.get(Stage.SELECT.key()); object(selection, "status", "candidateId", "reason");
        if (!text(selection, "status").equals("ready")) throw bad("选择阶段尚未就绪，不能裁决行动");
        String selectedId = id(selection, "candidateId"); text(selection, "reason");
        for (JsonNode candidate : candidates) if (text(candidate, "id").equals(selectedId)) {
            ObjectNode result = JSON.createObjectNode().put("status", "ready");
            ObjectNode action = result.putObject("action").put("module", text(candidate, "module"));
            action.set("arguments", candidate.get("arguments").deepCopy());
            if (candidate.has("moduleVersion")) action.set("moduleVersion", candidate.get("moduleVersion").deepCopy());
            return result;
        }
        throw bad("选择的行动不属于已准备候选");
    }

    private static ObjectNode feedback(ObjectNode request, ObjectNode view, ObjectNode receipt) {
        ObjectNode result = JSON.createObjectNode().put("status", "ready");
        result.set("after", view.deepCopy()); result.set("receipt", receipt.deepCopy());
        result.set("remainingGaps", optionalRows(request.get("scenario"), "remainingGaps").deepCopy());
        result.putArray("notes").add("仅记录本次提交回执与人物实际投影；提交不等于整件事情完成，未据此认定运输已经成功。");
        return result;
    }

    private static void append(ArrayNode target, ArrayNode values) { values.forEach(value -> target.add(value.deepCopy())); }
}
