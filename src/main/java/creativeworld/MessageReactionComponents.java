package creativeworld;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import static creativeworld.Protocol.*;
import static creativeworld.ExperienceComponent.Stage;

/** 人物仅按本人消息与明确策略作一次回应；不读取作者事实，也不自动级联。 */
public final class MessageReactionComponents {
    private static final List<ExperienceComponent> DELEGATES = BuiltinExperienceComponents.all();
    private MessageReactionComponents() {}

    public static List<ExperienceComponent> all() {
        return List.of(new Reaction("message-reaction-preparation", Stage.PREPARE),
            new Reaction("message-reaction-candidates", Stage.CANDIDATES),
            new Reaction("message-reaction-selection", Stage.SELECT),
            new Reaction("message-reaction-action", Stage.RESOLVE),
            new Reaction("message-reaction-feedback", Stage.FEEDBACK));
    }

    /** 单独的组件身份，保留旧消息分享组件的版本与行为。 */
    public static List<ExperienceComponent> stateResponses() {
        return List.of(new Reaction("state-reaction-preparation", Stage.PREPARE, true),
            new Reaction("state-reaction-candidates", Stage.CANDIDATES, true),
            new Reaction("state-reaction-selection", Stage.SELECT, true),
            new Reaction("state-reaction-action", Stage.RESOLVE, true),
            new Reaction("state-reaction-feedback", Stage.FEEDBACK, true));
    }

    private record Reaction(String id, Stage stage, boolean stateResponse) implements ExperienceComponent {
        Reaction(String id, Stage stage) { this(id, stage, false); }
        public String version() { return "1"; }
        public ObjectNode run(Context context) {
            Objects.requireNonNull(context, "消息回应上下文不能为空");
            ObjectNode request = context.request(), view = actorView(context.actorView());
            validateRequest(request);
            ObjectNode adapted = request.deepCopy(), scenario = JSON.createObjectNode();
            if (stage == Stage.FEEDBACK && request.get("scenario").has("remainingGaps"))
                scenario.set("remainingGaps", request.get("scenario").get("remainingGaps").deepCopy());
            adapted.set("scenario", scenario);
            Context delegated = new Context(adapted, view, context.outputs(), context.receipt());
            if (stage == Stage.FEEDBACK) return DELEGATES.get(stage.ordinal()).run(delegated);
            // 原组件统一核对请求身份、起始版本和提交前回执。
            DELEGATES.get(Stage.PREPARE.ordinal()).run(delegated);
            Assessment assessed = assess(view, request.get("scenario").get("message"), stateResponse);
            if (stage == Stage.PREPARE) return prepared(assessed);
            ObjectNode generated = candidates(assessed, stateResponse);
            if (stage == Stage.CANDIDATES || !text(generated, "status").equals("ready")) return generatedFor(stage, generated);
            JsonNode saved = context.outputs().path(Stage.CANDIDATES.key()).path("candidates");
            if (!canonical(saved).equals(canonical(generated.get("candidates"))))
                throw bad("消息回应候选不属于当前本人消息和策略的判断结果");
            if (stateResponse && stage == Stage.RESOLVE) {
                ObjectNode expected = DELEGATES.get(Stage.SELECT.ordinal()).run(delegated);
                if (!text(context.outputs().path(Stage.SELECT.key()), "candidateId").equals(text(expected, "candidateId")))
                    throw bad("状态回应选择必须与行为版本1的最高优先级、同分首项决定一致");
            }
            return DELEGATES.get(stage.ordinal()).run(delegated);
        }
    }

    private static void validateRequest(ObjectNode request) {
        object(request, "schemaVersion", "worldId", "requestId", "expectedRevision", "actor", "intent", "scenario", "components");
        version(request); id(request, "worldId"); id(request, "requestId"); integer(request, "expectedRevision");
        id(request, "actor"); text(request, "intent");
        JsonNode scenario = object(request.get("scenario"), Set.of("message"), Set.of("remainingGaps"));
        message(scenario.get("message"));
        if (scenario.has("remainingGaps")) for (JsonNode gap : array(scenario, "remainingGaps")) {
            if (gap.isTextual()) text(gap, "剩余缺口");
            else if (!gap.isObject() || gap.isEmpty()) throw bad("剩余缺口须为非空文本或对象");
        }
        ArrayNode components = array(request, "components");
        if (components.size() != Stage.values().length) throw bad("消息回应须配置五个阶段");
        Set<Stage> seen = EnumSet.noneOf(Stage.class);
        for (JsonNode row : components) {
            object(row, "stage", "id", "version"); id(row, "id"); moduleVersion(text(row, "version"));
            Stage stage;
            try { stage = Stage.valueOf(text(row, "stage").toUpperCase(Locale.ROOT)); }
            catch (IllegalArgumentException invalid) { throw bad("消息回应阶段未知"); }
            if (!seen.add(stage)) throw bad("消息回应阶段重复");
        }
        data(request);
    }

    static ObjectNode actorView(JsonNode incoming) {
        ObjectNode view = canonicalObject(object(incoming,
            Set.of("schemaVersion", "worldId", "title", "revision", "actor", "self", "nearby", "knowledge", "beliefs", "relationships"),
            Set.of("worldTime")));
        version(view); id(view, "worldId"); text(view, "title"); int revision = integer(view, "revision");
        String actor = id(view, "actor");
        Integer year = view.has("worldTime") ? WorldTime.from(view.get("worldTime")).year() : null;
        Set<String> selfFields = new HashSet<>(Set.of("id", "name", "place", "publicProfile", "privateProfile"));
        if (year != null) selfFields.addAll(Set.of("age", "publicProfileAsOfYear", "privateProfileAsOfYear", "publicProfileStatus", "privateProfileStatus"));
        JsonNode self = object(view.get("self"), selfFields, year == null ? Set.of() : Set.of("birthYear"));
        if (!id(self, "id").equals(actor)) throw bad("消息回应必须使用本人投影");
        text(self, "name"); id(self, "place"); profiles(self, true);
        if (year != null) {
            profileTime(self, "public", year); profileTime(self, "private", year);
            if (self.has("birthYear")) {
                int born = integer(self, "birthYear");
                if (born > year || integer(self, "age") != year - born) throw bad("人物年龄与共同时间不符");
            } else if (!self.get("age").isNull()) throw bad("未知出生年不能推导年龄");
        }
        Set<String> nearbyIds = new HashSet<>();
        for (JsonNode person : array(view, "nearby")) {
            Set<String> fields = new HashSet<>(Set.of("id", "name", "place", "publicProfile"));
            if (year != null) fields.addAll(Set.of("age", "publicProfileAsOfYear", "publicProfileStatus"));
            object(person, fields, Set.of());
            String who = id(person, "id"); text(person, "name"); profiles(person, false);
            if (who.equals(actor) || !nearbyIds.add(who) || !id(person, "place").equals(text(self, "place")))
                throw bad("消息回应的同地人物投影无效");
            if (year != null) {
                profileTime(person, "public", year); if (!person.get("age").isNull()) integer(person, "age");
            }
        }
        Set<String> facts = new HashSet<>();
        for (JsonNode known : array(view, "knowledge")) {
            Set<String> fields = new HashSet<>(Set.of("factId", "value", "source", "revision"));
            if (year != null) fields.addAll(Set.of("learnedAtYear", "asOfYear"));
            object(known, fields, Set.of()); scalar(known.get("value"), "知识值"); text(known, "source");
            if (!facts.add(id(known, "factId")) || integer(known, "revision") > revision) throw bad("人物知识标识重复或来自未来版本");
            if (year != null && (integer(known, "asOfYear") > integer(known, "learnedAtYear") || integer(known, "learnedAtYear") > year))
                throw bad("人物知识年份不符合共同时间");
        }
        Set<String> related = new HashSet<>();
        for (JsonNode relation : array(view, "relationships")) {
            Set<String> fields = new HashSet<>(Set.of("to", "value", "revision"));
            if (year != null) fields.add("occurredAtYear");
            object(relation, fields, year == null ? Set.of() : Set.of("basis")); text(relation, "value");
            if (!related.add(id(relation, "to")) || integer(relation, "revision") > revision) throw bad("人物关系重复或来自未来版本");
            if (year != null && integer(relation, "occurredAtYear") > year) throw bad("人物关系来自未来年份");
            if (relation.has("basis")) text(relation, "basis");
        }
        array(view, "beliefs"); data(view); return view;
    }

    private static void profiles(JsonNode person, boolean own) {
        if (!person.path("publicProfile").isObject() || (own && !person.path("privateProfile").isObject())) throw bad("人物资料须为对象");
    }
    private static void profileTime(JsonNode person, String visibility, int year) {
        int asOf = integer(person, visibility + "ProfileAsOfYear");
        if (asOf > year || !text(person, visibility + "ProfileStatus").equals(asOf == year ? "current" : "historical"))
            throw bad("人物资料快照年份与状态不符");
    }

    static ObjectNode message(JsonNode incoming) {
        ObjectNode message = canonicalObject(object(incoming, "factId", "sourcePersonId", "receivedRevision"));
        id(message, "factId"); text(message, "sourcePersonId"); integer(message, "receivedRevision"); return message;
    }

    record Eligible(int index, ObjectNode rule, ArrayNode evidence) {}
    record Assessment(ObjectNode view, ObjectNode policy, ObjectNode message, List<Eligible> eligible, ArrayNode evidence, ArrayNode gaps) {}

    static Assessment assess(ObjectNode view, JsonNode incomingMessage) {
        return assess(view, incomingMessage, false);
    }

    /** 新的状态回应显式启用；旧分享回应的字段与效果语义保持原样。 */
    static Assessment assess(ObjectNode view, JsonNode incomingMessage, boolean stateResponse) {
        ObjectNode message = message(incomingMessage);
        ArrayNode evidence = JSON.createArrayNode(), gaps = JSON.createArrayNode();
        JsonNode profile = view.path("self").path("privateProfile");
        if (!profile.has("responsePolicy")) {
            gap(gaps, "policyUnknown", "/self/privateProfile/responsePolicy", "本人尚无明确回应策略", null);
            return new Assessment(view, null, message, List.of(), evidence, gaps);
        }
        ObjectNode policy = canonicalObject(object(profile.get("responsePolicy"), "goalId", "goal", "rules"));
        id(policy, "goalId"); text(policy, "goal"); validateRules(array(policy, "rules"), stateResponse);
        proof(evidence, view, "/self/id", "回应者本人");
        proof(evidence, view, "/self/privateProfile/responsePolicy/goalId", "本人回应目标标识");
        proof(evidence, view, "/self/privateProfile/responsePolicy/goal", "本人回应目标");
        String actor = text(view, "actor"), source = text(message, "sourcePersonId"), factId = text(message, "factId");
        if (source.startsWith("@") || source.equals(actor)) {
            gap(gaps, "invalidMessageSource", "/knowledge", "触发消息须来自另一位普通人物，不能把初始资料或现场观察当作他人消息", null);
            return new Assessment(view, policy, message, List.of(), evidence, gaps);
        }
        int knownIndex = index(array(view, "knowledge"), "factId", factId);
        if (knownIndex < 0) {
            gap(gaps, "messageUnknown", "/knowledge", "本人尚未收到这项消息", null);
            return new Assessment(view, policy, message, List.of(), evidence, gaps);
        }
        JsonNode known = view.get("knowledge").get(knownIndex);
        if (!text(known, "source").equals(source) || integer(known, "revision") != integer(message, "receivedRevision")) {
            gap(gaps, "messageChanged", "/knowledge/" + knownIndex, "指定消息与本人当前知识来源或接收版本不一致", null);
            return new Assessment(view, policy, message, List.of(), evidence, gaps);
        }
        int sourceIndex = index(array(view, "nearby"), "id", source);
        if (sourceIndex < 0) {
            gap(gaps, "sourceNotNearby", "/nearby", "消息来源当前不在同一地点，本次不能直接回应或分享", null);
            return new Assessment(view, policy, message, List.of(), evidence, gaps);
        }
        knowledgeProof(evidence, view, knownIndex, "收到的触发消息");
        proof(evidence, view, "/self/place", "本人当前地点");
        proof(evidence, view, "/nearby/" + sourceIndex + "/id", "同地的消息来源人物");
        proof(evidence, view, "/nearby/" + sourceIndex + "/place", "消息来源当前地点");
        List<Eligible> eligible = new ArrayList<>();
        ArrayNode rules = array(policy, "rules");
        for (int ruleIndex = 0; ruleIndex < rules.size(); ruleIndex++) {
            ObjectNode rule = (ObjectNode) rules.get(ruleIndex); String ruleId = text(rule, "id");
            if (!text(rule.get("when"), "factId").equals(factId)) continue;
            ArrayNode ruleProof = evidence.deepCopy(), ruleGaps = JSON.createArrayNode();
            proof(ruleProof, view, "/self/privateProfile/responsePolicy/rules/" + ruleIndex, "本人回应规则与触发条件");
            if (!condition(view, rule.get("when"), ruleId, ruleProof, ruleGaps)) { append(gaps, ruleGaps); continue; }
            boolean available = true;
            for (JsonNode condition : array(rule, "conditions")) available &= condition(view, condition, ruleId, ruleProof, ruleGaps);
            if (rule.has("stateChanges")) for (JsonNode change : array(rule, "stateChanges")) {
                ObjectNode oldValue = JSON.createObjectNode().put("factId", text(change, "factId"));
                oldValue.set("equals", change.get("expectedValue").deepCopy());
                available &= condition(view, oldValue, ruleId, ruleProof, ruleGaps);
            }
            for (JsonNode fact : array(rule, "shareFactIds")) {
                int shareIndex = index(array(view, "knowledge"), "factId", fact.asText());
                if (shareIndex < 0 || unknown(view.get("knowledge").get(shareIndex).get("value"))) {
                    available = false; gap(ruleGaps, "shareUnknown", "/knowledge", "本人没有可供分享的明确知识：" + fact.asText(), ruleId);
                } else knowledgeProof(ruleProof, view, shareIndex, "本人准备分享的知识及来源");
            }
            if (rule.has("relationshipValue")) {
                int relation = index(array(view, "relationships"), "to", source);
                if (relation >= 0) proof(ruleProof, view, "/relationships/" + relation, "本人当前对消息来源的关系依据");
            }
            if (available) eligible.add(new Eligible(ruleIndex, rule, ruleProof));
            append(gaps, ruleGaps);
        }
        if (eligible.isEmpty() && gaps.isEmpty()) gap(gaps, "noMatchingRule", "/self/privateProfile/responsePolicy/rules", "没有匹配本次消息的回应规则", null);
        return new Assessment(view, policy, message, List.copyOf(eligible), evidence, gaps);
    }

    private static void validateRules(ArrayNode rules, boolean stateResponse) {
        Set<String> ids = new HashSet<>();
        for (JsonNode rule : rules) {
            object(rule, Set.of("id", "priority", "when", "conditions", "shareFactIds", "description"),
                stateResponse ? Set.of("relationshipValue", "stateChanges") : Set.of("relationshipValue"));
            if (!ids.add(id(rule, "id"))) throw bad("回应规则标识重复");
            JsonNode priority = rule.get("priority");
            if (!priority.isIntegralNumber() || !priority.canConvertToInt()) throw bad("回应优先级须为整数");
            text(rule, "description"); validateCondition(rule.get("when"));
            for (JsonNode condition : array(rule, "conditions")) validateCondition(condition);
            Set<String> shared = new HashSet<>();
            for (JsonNode fact : array(rule, "shareFactIds")) if (!shared.add(id(fact, "shareFactIds"))) throw bad("回应分享事实重复");
            if (rule.has("relationshipValue")) text(rule, "relationshipValue");
            if (rule.has("stateChanges")) {
                ArrayNode changes = array(rule, "stateChanges");
                if (changes.isEmpty()) throw bad("状态回应需要至少一项明确变化");
                Set<String> changed = new HashSet<>();
                for (JsonNode row : changes) {
                    JsonNode change = object(row, "factId", "expectedValue", "value");
                    if (!changed.add(id(change, "factId"))) throw bad("状态回应不能重复改变同一事实");
                    scalar(change.get("expectedValue"), "状态旧值"); scalar(change.get("value"), "状态新值");
                    if (unknown(change.get("expectedValue")) || unknown(change.get("value"))
                        || canonical(change.get("expectedValue")).equals(canonical(change.get("value"))))
                        throw bad("状态回应需要明确且不同的新旧值");
                }
            }
            if (shared.isEmpty() && !rule.has("relationshipValue") && !rule.has("stateChanges"))
                throw bad("回应规则至少须有知识分享、关系后果或明确状态决定");
        }
    }
    private static void validateCondition(JsonNode condition) {
        object(condition, "factId", "equals"); id(condition, "factId"); scalar(condition.get("equals"), "条件值");
    }
    private static boolean condition(ObjectNode view, JsonNode condition, String ruleId, ArrayNode evidence, ArrayNode gaps) {
        String factId = text(condition, "factId"); int knownIndex = index(array(view, "knowledge"), "factId", factId);
        if (knownIndex < 0 || unknown(view.get("knowledge").get(knownIndex).get("value"))) {
            gap(gaps, "conditionUnknown", "/knowledge", "本人尚无条件的明确知识：" + factId, ruleId); return false;
        }
        JsonNode actual = view.get("knowledge").get(knownIndex).get("value");
        if (!canonical(actual).equals(canonical(condition.get("equals")))) {
            gap(gaps, "conditionUnmet", "/knowledge/" + knownIndex + "/value", "本人已知条件与规则不符：" + factId, ruleId); return false;
        }
        knowledgeProof(evidence, view, knownIndex, "本人已知且匹配的回应条件"); return true;
    }

    private static ObjectNode prepared(Assessment assessed) {
        ObjectNode result = JSON.createObjectNode().put("status", assessed.eligible.isEmpty() ? "needsInput" : "ready");
        result.set("evidence", assessed.evidence.deepCopy()); result.set("gaps", assessed.gaps.deepCopy()); return result;
    }
    private static ObjectNode candidates(Assessment assessed, boolean stateResponse) {
        ObjectNode result = prepared(assessed); ArrayNode rows = result.putArray("candidates"), evidence = (ArrayNode) result.get("evidence");
        for (Eligible eligible : assessed.eligible) {
            JsonNode rule = eligible.rule;
            if (stateResponse && !rule.has("stateChanges")) continue;
            ObjectNode candidate = rows.addObject().put("id", text(rule, "id")).put("module", stateResponse ? "message-state-response" : "message-response").put("moduleVersion", "1")
                .put("priority", rule.get("priority").intValue()).put("description", text(assessed.policy, "goal") + "；" + text(rule, "description"));
            ObjectNode arguments = candidate.putObject("arguments");
            if (!stateResponse) arguments.put("ruleId", text(rule, "id"));
            arguments.set("message", assessed.message.deepCopy());
            candidate.set("evidence", eligible.evidence.deepCopy()); append(evidence, eligible.evidence);
        }
        if (rows.isEmpty()) result.put("status", "needsInput").put("reason", "本人消息或回应依据尚未满足，未编造动作。");
        return result;
    }
    private static ObjectNode generatedFor(Stage stage, ObjectNode generated) {
        if (stage != Stage.CANDIDATES) generated.remove("candidates"); return generated;
    }
    static int index(ArrayNode values, String field, String value) {
        for (int index = 0; index < values.size(); index++) if (text(values.get(index), field).equals(value)) return index;
        return -1;
    }
    private static boolean unknown(JsonNode value) { return value == null || value.isNull() || (value.isTextual() && value.asText().isBlank()); }
    private static void knowledgeProof(ArrayNode evidence, ObjectNode view, int index, String label) {
        String prefix = "/knowledge/" + index;
        for (String field : List.of("factId", "value", "source", "revision")) proof(evidence, view, prefix + "/" + field, label + "：" + field);
        if (view.has("worldTime")) for (String field : List.of("learnedAtYear", "asOfYear")) proof(evidence, view, prefix + "/" + field, label + "：" + field);
    }
    private static void proof(ArrayNode evidence, ObjectNode view, String path, String label) {
        JsonNode value = view.at(path);
        if (value.isMissingNode()) throw bad("消息回应依据路径不存在：" + path);
        evidence.addObject().put("path", path).put("label", label).set("equals", value.deepCopy());
    }
    private static void gap(ArrayNode gaps, String kind, String path, String reason, String ruleId) {
        ObjectNode gap = gaps.addObject().put("kind", kind).put("path", path).put("reason", reason);
        if (ruleId != null) gap.put("ruleId", ruleId);
    }
    private static void append(ArrayNode to, ArrayNode from) { from.forEach(value -> to.add(value.deepCopy())); }
}
