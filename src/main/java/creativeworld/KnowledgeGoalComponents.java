package creativeworld;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import static creativeworld.Protocol.*;
import static creativeworld.ExperienceComponent.Stage;

/** 根据本人知识生成一项下一步行动；不访问世界真值、不推断情绪、不替内核认定成功。 */
public final class KnowledgeGoalComponents {
    private static final List<ExperienceComponent> DELEGATES = BuiltinExperienceComponents.all();
    private KnowledgeGoalComponents() {}

    public static List<ExperienceComponent> all() {
        return List.of(new GoalComponent("knowledge-goal-preparation", Stage.PREPARE),
            new GoalComponent("knowledge-goal-candidates", Stage.CANDIDATES),
            new GoalComponent("knowledge-goal-selection", Stage.SELECT),
            new GoalComponent("knowledge-goal-action", Stage.RESOLVE),
            new GoalComponent("knowledge-goal-feedback", Stage.FEEDBACK));
    }

    private record GoalComponent(String id, Stage stage) implements ExperienceComponent {
        public String version() { return "1"; }
        public ObjectNode run(Context context) {
            Objects.requireNonNull(context, "目标演绎上下文不能为空");
            ObjectNode request = context.request(), view = context.actorView(), receipt = context.receipt();
            validateContext(request, view, receipt, stage);
            return switch (stage) {
                case PREPARE -> preparation(view);
                case CANDIDATES -> candidates(request.path("scenario").path("goal"), view);
                case SELECT, RESOLVE -> {
                    // 复用现有选择/转换合同；旧组件的scenario不支持goal，传独立的适配副本。
                    ObjectNode adapted = request.deepCopy(); adapted.set("scenario", JSON.createObjectNode());
                    yield DELEGATES.get(stage.ordinal()).run(new Context(adapted, view, context.outputs(), receipt));
                }
                case FEEDBACK -> feedback(request.get("scenario"), view, receipt);
            };
        }
    }

    private static void validateContext(ObjectNode request, ObjectNode view, ObjectNode receipt, Stage stage) {
        object(request, "schemaVersion", "worldId", "requestId", "expectedRevision", "actor", "intent", "scenario", "components");
        version(request); id(request, "worldId"); id(request, "requestId"); integer(request, "expectedRevision");
        String actor = id(request, "actor"); text(request, "intent"); data(request);
        ArrayNode components = array(request, "components");
        if (components.size() != Stage.values().length) throw bad("目标演绎必须配置五个阶段");
        Set<Stage> seen = EnumSet.noneOf(Stage.class);
        for (JsonNode row : components) {
            object(row, "stage", "id", "version"); id(row, "id"); text(row, "version");
            Stage configured;
            try { configured = Stage.valueOf(text(row, "stage").toUpperCase(Locale.ROOT)); }
            catch (IllegalArgumentException invalid) { throw bad("目标演绎阶段未知"); }
            if (!seen.add(configured)) throw bad("目标演绎阶段重复");
        }
        JsonNode scenario = object(request.get("scenario"), Set.of("goal"), Set.of("remainingGaps"));
        JsonNode goal = object(scenario.get("goal"), "id", "kind", "factId", "locationFactId", "label");
        id(goal, "id"); id(goal, "factId"); id(goal, "locationFactId"); text(goal, "label");
        if (!text(goal, "kind").equals("observeLocatedFact")) throw bad("目标类型不支持");
        if (scenario.has("remainingGaps")) for (JsonNode gap : array(scenario, "remainingGaps")) {
            if (gap.isTextual()) text(gap, "剩余缺口");
            else if (!gap.isObject() || gap.isEmpty()) throw bad("剩余缺口须为非空文本或对象");
        }

        object(view, Set.of("schemaVersion", "worldId", "title", "revision", "actor", "self", "nearby", "knowledge", "beliefs", "relationships"), Set.of("worldTime"));
        version(view); text(view, "title");
        if (!text(view, "worldId").equals(text(request, "worldId")) || !text(view, "actor").equals(actor))
            throw bad("目标演绎人物视图身份不一致");
        int revision = integer(view, "revision");
        Integer year = view.has("worldTime") ? WorldTime.from(view.get("worldTime")).year() : null;
        Set<String> personFields = Set.of("age", "publicProfileAsOfYear", "privateProfileAsOfYear", "publicProfileStatus", "privateProfileStatus");
        Set<String> selfRequired = new HashSet<>(Set.of("id", "name", "place", "publicProfile", "privateProfile"));
        if (year != null) selfRequired.addAll(personFields);
        JsonNode self = object(view.get("self"), selfRequired, year == null ? Set.of() : Set.of("birthYear"));
        if (!id(self, "id").equals(actor)) throw bad("目标演绎只能使用本人的人物视图");
        text(self, "name"); id(self, "place");
        if (!self.path("publicProfile").isObject() || !self.path("privateProfile").isObject()) throw bad("人物资料必须是对象");
        if (year != null) {
            profileTime(self, "public", year); profileTime(self, "private", year);
            if (self.has("birthYear")) {
                int born = integer(self, "birthYear");
                if (born > year || integer(self, "age") != year - born) throw bad("人物年龄不符合共同世界时间");
            } else if (!self.path("age").isNull()) throw bad("未知出生时间不能推断年龄");
        }
        data(view); array(view, "beliefs");
        Set<String> people = new HashSet<>();
        for (JsonNode person : array(view, "nearby")) {
            Set<String> nearbyRequired = new HashSet<>(Set.of("id", "name", "place", "publicProfile"));
            if (year != null) nearbyRequired.addAll(Set.of("age", "publicProfileAsOfYear", "publicProfileStatus"));
            object(person, nearbyRequired, Set.of());
            String who = id(person, "id"); text(person, "name");
            if (who.equals(actor) || !people.add(who) || !id(person, "place").equals(text(self, "place"))
                || !person.path("publicProfile").isObject()) throw bad("同地人物投影无效");
            if (year != null) {
                profileTime(person, "public", year);
                if (!person.get("age").isNull()) integer(person, "age");
            }
        }
        Set<String> facts = new HashSet<>();
        for (JsonNode known : array(view, "knowledge")) {
            Set<String> knownRequired = new HashSet<>(Set.of("factId", "value", "source", "revision"));
            if (year != null) knownRequired.addAll(Set.of("learnedAtYear", "asOfYear"));
            object(known, knownRequired, Set.of());
            if (!facts.add(id(known, "factId"))) throw bad("人物知识事实重复");
            scalar(known.get("value"), "知识值"); text(known, "source");
            if (integer(known, "revision") > revision) throw bad("人物知识来自未来版本");
            if (year != null && (integer(known, "asOfYear") > integer(known, "learnedAtYear")
                || integer(known, "learnedAtYear") > year)) throw bad("人物知识时间不符合共同世界时间");
        }
        Set<String> relations = new HashSet<>();
        for (JsonNode relation : array(view, "relationships")) {
            Set<String> relationRequired = new HashSet<>(Set.of("to", "value", "revision"));
            if (year != null) relationRequired.add("occurredAtYear");
            object(relation, relationRequired, year == null ? Set.of() : Set.of("basis")); text(relation, "value");
            if (!relations.add(id(relation, "to")) || integer(relation, "revision") > revision) throw bad("人物关系投影无效");
            if (year != null && integer(relation, "occurredAtYear") > year) throw bad("人物关系来自未来时间");
            if (relation.has("basis")) text(relation, "basis");
        }
        JsonNode profile = self.get("privateProfile");
        if (profile.has("knownRoutes")) for (JsonNode route : array(profile, "knownRoutes")) {
            object(route, "from", "to", "evidenceFactId");
            id(route, "from"); id(route, "to"); id(route, "evidenceFactId");
        }
        if (stage == Stage.FEEDBACK) {
            object(receipt, "schemaVersion", "worldId", "requestId", "revision", "status"); version(receipt);
            if (!text(receipt, "worldId").equals(text(request, "worldId"))
                || !text(receipt, "requestId").equals(text(request, "requestId"))
                || !text(receipt, "status").equals("committed") || integer(receipt, "revision") != revision
                || revision <= integer(request, "expectedRevision")) throw bad("目标反馈不属于本次正式提交版本");
        } else if (revision != integer(request, "expectedRevision") || !receipt.isEmpty())
            throw bad("目标推导的起始版本或提交前回执无效");
    }

    private static void profileTime(JsonNode person, String visibility, int year) {
        int asOf = integer(person, visibility + "ProfileAsOfYear");
        if (asOf > year || !text(person, visibility + "ProfileStatus").equals(asOf == year ? "current" : "historical"))
            throw bad("人物资料快照时间或状态无效");
    }

    private static ObjectNode preparation(ObjectNode view) {
        ObjectNode result = JSON.createObjectNode().put("status", "ready");
        ArrayNode evidence = result.putArray("evidence");
        evidence(evidence, view, "/self/id", "行动者本人");
        evidence(evidence, view, "/self/place", "本人当前地点");
        result.putArray("gaps"); return result;
    }

    private static ObjectNode candidates(JsonNode goal, ObjectNode view) {
        ObjectNode result = JSON.createObjectNode().put("status", "ready");
        ArrayNode candidates = result.putArray("candidates"), evidence = result.putArray("evidence"); result.putArray("gaps");
        int observed = observedIndex(goal, view);
        if (observed >= 0) {
            result.put("status", "satisfied").put("reason", "目标事实已有本人现场观察记录，本轮不再提交行动。");
            knowledgeEvidence(evidence, view, observed); return result;
        }
        int locationIndex = knowledgeIndex(view, text(goal, "locationFactId"));
        if (locationIndex < 0) {
            ArrayNode nearby = array(view, "nearby");
            for (int index = 0; index < nearby.size(); index++) {
                String target = text(nearby.get(index), "id"); int relation = relationshipIndex(view, target);
                ObjectNode candidate = candidate(candidates, "ask-location-" + index, "ask", relation >= 0 ? 1 : 0,
                    "向同地人物询问目标所在位置；未假定对方知情。");
                candidate.putObject("arguments").put("target", target).put("factId", text(goal, "locationFactId"));
                ArrayNode proof = candidate.putArray("evidence");
                evidence(proof, view, "/self/place", "本人当前地点");
                evidence(proof, view, "/nearby/" + index + "/id", "当前可询问的同地人物");
                evidence(proof, view, "/nearby/" + index + "/place", "询问对象当前地点");
                if (relation >= 0) {
                    evidence(proof, view, "/relationships/" + relation + "/to", "本人已有关系的对象");
                    evidence(proof, view, "/relationships/" + relation + "/value", "本人对该关系的已知描述");
                }
            }
            if (candidates.isEmpty()) return missing(result, goal, "unknownLocation", "尚不知道目标所在位置，当前也没有同地询问对象。");
            return result;
        }
        JsonNode value = view.path("knowledge").get(locationIndex).get("value");
        if (!value.isTextual() || value.asText().isBlank() || value.asText().startsWith("@"))
            return missing(result, goal, "invalidLocation", "位置消息没有可用的地点标识，不能据此安排移动。");
        String destination = value.asText(), current = text(view.get("self"), "place");
        if (current.equals(destination)) {
            ObjectNode candidate = candidate(candidates, "observe-target", "observe", 0, "已在已知位置，尝试现场观察目标事实。");
            candidate.putObject("arguments").put("factId", text(goal, "factId"));
            ArrayNode proof = candidate.putArray("evidence");
            evidence(proof, view, "/self/place", "本人已经到达的地点"); knowledgeEvidence(proof, view, locationIndex);
            return result;
        }
        List<Edge> path = knownPath(view, current, destination);
        if (path.isEmpty()) return missing(result, goal, "unknownRoute", "本人已知路线不足以从当前地点到达目标位置，未借用作者地图。");
        ObjectNode candidate = candidate(candidates, "travel-first-hop", "travel", 0, "沿本人有知识依据的路线前往第一处相邻地点。");
        candidate.putObject("arguments").put("to", path.get(0).to);
        ArrayNode proof = candidate.putArray("evidence");
        evidence(proof, view, "/self/place", "本人当前地点"); knowledgeEvidence(proof, view, locationIndex);
        for (Edge edge : path) {
            String prefix = "/self/privateProfile/knownRoutes/" + edge.routeIndex;
            for (String field : List.of("from", "to", "evidenceFactId")) evidence(proof, view, prefix + "/" + field, "本人记录的路线：" + field);
            knowledgeEvidence(proof, view, edge.knowledgeIndex);
        }
        return result;
    }

    private record Edge(int routeIndex, int knowledgeIndex, String from, String to) {}
    private static List<Edge> knownPath(ObjectNode view, String from, String to) {
        JsonNode routes = view.path("self").path("privateProfile").path("knownRoutes");
        Map<String, List<Edge>> outgoing = new LinkedHashMap<>();
        for (int index = 0; index < routes.size(); index++) {
            JsonNode route = routes.get(index); int known = knowledgeIndex(view, text(route, "evidenceFactId"));
            if (known < 0 || !view.path("knowledge").get(known).path("value").isBoolean()
                || !view.path("knowledge").get(known).path("value").booleanValue()) continue;
            Edge edge = new Edge(index, known, text(route, "from"), text(route, "to"));
            outgoing.computeIfAbsent(edge.from, ignored -> new ArrayList<>()).add(edge);
        }
        Map<String, Edge> previous = new LinkedHashMap<>(); previous.put(from, null);
        ArrayDeque<String> queue = new ArrayDeque<>(); queue.add(from);
        while (!queue.isEmpty() && !previous.containsKey(to)) {
            for (Edge edge : outgoing.getOrDefault(queue.remove(), List.of())) if (!previous.containsKey(edge.to)) {
                previous.put(edge.to, edge); queue.add(edge.to);
            }
        }
        if (!previous.containsKey(to)) return List.of();
        ArrayList<Edge> result = new ArrayList<>();
        for (String cursor = to; !cursor.equals(from); ) {
            Edge edge = previous.get(cursor); result.add(edge); cursor = edge.from;
        }
        Collections.reverse(result); return result;
    }

    private static ObjectNode feedback(JsonNode scenario, ObjectNode view, ObjectNode receipt) {
        JsonNode goal = scenario.get("goal"); int observed = observedIndex(goal, view);
        ObjectNode result = JSON.createObjectNode().put("status", "ready");
        result.set("after", view.deepCopy()); result.set("receipt", receipt.deepCopy());
        ArrayNode gaps = JSON.createArrayNode();
        if (scenario.has("remainingGaps")) for (JsonNode gap : array(scenario, "remainingGaps")) {
            // 更新本组件已有的目标缺口，保留调用者声明的其他任务，避免重复积累过期状态。
            boolean ownGap = gap.path("goalId").asText().equals(text(goal, "id"))
                && Set.of("unknownLocation", "invalidLocation", "unknownRoute", "observationPending").contains(gap.path("kind").asText());
            if (!ownGap) gaps.add(gap.deepCopy());
        }
        result.set("remainingGaps", gaps); ArrayNode notes = result.putArray("notes"), evidence = result.putArray("evidence");
        if (observed >= 0) {
            knowledgeEvidence(evidence, view, observed); notes.add("目标“" + text(goal, "label") + "”已有本人现场观察记录。");
        } else {
            int location = knowledgeIndex(view, text(goal, "locationFactId"));
            String reason = location < 0 ? "尚未得知目标所在位置。" : "目标事实尚未被本人现场观察。";
            gaps.addObject().put("goalId", text(goal, "id")).put("kind", "observationPending").put("reason", reason);
            notes.add("本次行动已提交，但现场观察目标尚未完成。");
        }
        notes.add("仅确认本次行动后的实际人物投影；现场查证不代表运输或其他业务目标已经完成。");
        return result;
    }

    private static int observedIndex(JsonNode goal, ObjectNode view) {
        int index = knowledgeIndex(view, text(goal, "factId"));
        if (index < 0) return -1;
        JsonNode known = view.path("knowledge").get(index);
        if (!text(known, "source").equals("@world")) return -1;
        // 年份和版本共同限定本次核实；不读取作者真值来判断本人旧认识。
        if (view.has("worldTime") && (integer(known, "asOfYear") != WorldTime.from(view.get("worldTime")).year()
            || integer(known, "revision") != integer(view, "revision"))) return -1;
        return index;
    }
    private static int knowledgeIndex(ObjectNode view, String factId) {
        ArrayNode known = array(view, "knowledge");
        for (int index = 0; index < known.size(); index++) if (text(known.get(index), "factId").equals(factId)) return index;
        return -1;
    }
    private static int relationshipIndex(ObjectNode view, String who) {
        ArrayNode relations = array(view, "relationships");
        for (int index = 0; index < relations.size(); index++) if (text(relations.get(index), "to").equals(who)) return index;
        return -1;
    }
    private static ObjectNode candidate(ArrayNode candidates, String id, String module, int priority, String description) {
        return candidates.addObject().put("id", id).put("module", module).put("priority", priority).put("description", description);
    }
    private static ObjectNode missing(ObjectNode result, JsonNode goal, String kind, String reason) {
        result.put("status", "needsInput").put("reason", reason);
        ((ArrayNode) result.get("gaps")).addObject().put("goalId", text(goal, "id")).put("kind", kind).put("reason", reason);
        return result;
    }
    private static void knowledgeEvidence(ArrayNode evidence, ObjectNode view, int index) {
        for (String field : List.of("factId", "value", "source")) evidence(evidence, view, "/knowledge/" + index + "/" + field, "本人的知识快照：" + field);
        if (view.has("worldTime")) {
            evidence(evidence, view, "/worldTime/year", "当前共同世界时间");
            for (String field : List.of("asOfYear", "learnedAtYear"))
                evidence(evidence, view, "/knowledge/" + index + "/" + field, "知识快照的时间依据：" + field);
        }
    }
    private static void evidence(ArrayNode evidence, ObjectNode view, String path, String label) {
        JsonNode actual = view.at(path);
        if (actual.isMissingNode()) throw bad("目标候选的依据指针不存在：" + path);
        evidence.addObject().put("path", path).put("label", label).set("equals", actual.deepCopy());
    }
}
