package creativeworld;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import static creativeworld.Protocol.*;

/** 本人按明确消息与策略选择一项状态决定；执行资格仍由共同内核裁决。 */
public final class MessageStateResponseModule implements ActionModule {
    public String id() { return "message-state-response"; }
    public String version() { return "1"; }

    public JsonNode propose(JsonNode actorView, JsonNode input) {
        ObjectNode view = MessageReactionComponents.actorView(actorView);
        ObjectNode frozen = canonicalObject(object(input, "schemaVersion", "worldId", "requestId", "expectedRevision", "actor", "message"));
        Protocol.version(frozen); Protocol.id(frozen, "worldId"); Protocol.id(frozen, "requestId");
        String actor = Protocol.id(frozen, "actor");
        if (!text(frozen, "worldId").equals(text(view, "worldId")) || !actor.equals(text(view, "actor"))
            || integer(frozen, "expectedRevision") != integer(view, "revision"))
            throw bad("状态回应输入与本人身份或世界版本不符");
        MessageReactionComponents.Assessment assessed = MessageReactionComponents.assess(view, frozen.get("message"), true);
        MessageReactionComponents.Eligible selected = assessed.eligible().stream()
            .filter(row -> row.rule().has("stateChanges"))
            .max(Comparator.comparingInt((MessageReactionComponents.Eligible row) -> row.rule().get("priority").intValue())
                .thenComparingInt(row -> -row.index()))
            .orElseThrow(() -> bad("本人消息、明确策略或状态决定前提不足，不能执行"));
        JsonNode rule = selected.rule(), message = assessed.message();
        String source = text(message, "sourcePersonId");
        String reason = text(assessed.policy(), "goalId") + "：" + text(assessed.policy(), "goal") + "；"
            + text(rule, "description") + "；按本人收到的消息与规则选择" + text(rule, "id")
            + "，消息接收版本" + integer(message, "receivedRevision");
        ObjectNode proposal = JSON.createObjectNode();
        for (String field : List.of("schemaVersion", "worldId", "requestId", "expectedRevision", "actor"))
            proposal.set(field, frozen.get(field).deepCopy());
        proposal.put("reason", reason);
        ArrayNode effects = proposal.putArray("effects");
        for (JsonNode change : array(rule, "stateChanges")) {
            ObjectNode update = effects.addObject().put("type", "fact.change").put("factId", text(change, "factId"));
            update.set("expectedValue", change.get("expectedValue").deepCopy()); update.set("value", change.get("value").deepCopy());
        }
        // 先完成决定并现场复核，再转述本人新认识；无法观察时整笔提交拒绝。
        Set<String> required = new LinkedHashSet<>(); required.add(text(message, "factId"));
        for (JsonNode change : array(rule, "stateChanges")) {
            String factId = text(change, "factId"); required.add(factId);
            effects.addObject().put("type", "knowledge.learn").put("personId", actor).put("factId", factId).put("sourcePersonId", "@world");
        }
        for (JsonNode condition : array(rule, "conditions")) required.add(text(condition, "factId"));
        for (JsonNode share : array(rule, "shareFactIds")) {
            required.add(share.asText());
            effects.addObject().put("type", "knowledge.share").put("from", actor).put("to", source).put("factId", share.asText());
        }
        if (rule.has("relationshipValue")) {
            int index = MessageReactionComponents.index(array(view, "relationships"), "to", source);
            JsonNode expected = index < 0 ? NullNode.instance : view.get("relationships").get(index).get("value");
            ObjectNode relation = effects.addObject().put("type", "relationship.change").put("from", actor).put("to", source);
            relation.set("expectedValue", expected.deepCopy()); relation.put("value", text(rule, "relationshipValue")).put("basis", reason);
        }
        ArrayNode requiresKnown = proposal.putArray("requiresKnown"); required.forEach(requiresKnown::add);
        return canonicalObject(proposal);
    }
}
