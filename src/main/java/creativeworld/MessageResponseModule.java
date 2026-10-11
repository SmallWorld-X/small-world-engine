package creativeworld;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import static creativeworld.Protocol.*;

/** 复核同一消息与规则后，仅分享本人知识或改变本人对来源人物的关系。 */
public final class MessageResponseModule implements ActionModule {
    public String id() { return "message-response"; }
    public String version() { return "1"; }

    public JsonNode propose(JsonNode actorView, JsonNode input) {
        ObjectNode view = MessageReactionComponents.actorView(actorView);
        ObjectNode frozen = canonicalObject(object(input, "schemaVersion", "worldId", "requestId", "expectedRevision", "actor", "message", "ruleId"));
        Protocol.version(frozen); Protocol.id(frozen, "worldId"); Protocol.id(frozen, "requestId");
        String actor = Protocol.id(frozen, "actor"), ruleId = Protocol.id(frozen, "ruleId");
        if (!text(frozen, "worldId").equals(text(view, "worldId")) || !actor.equals(text(view, "actor"))
            || integer(frozen, "expectedRevision") != integer(view, "revision")) throw bad("消息回应输入身份或版本与本人投影不符");
        MessageReactionComponents.Assessment assessed = MessageReactionComponents.assess(view, frozen.get("message"));
        MessageReactionComponents.Eligible selected = assessed.eligible().stream()
            .filter(rule -> text(rule.rule(), "id").equals(ruleId)).findFirst()
            .orElseThrow(() -> bad("指定回应规则缺少本人消息或完整前提，不能正式执行"));
        JsonNode rule = selected.rule(), message = assessed.message(); String source = text(message, "sourcePersonId");
        if (source.equals(actor)) throw bad("不能向本人分享知识");
        String reason = text(assessed.policy(), "goalId") + "：" + text(assessed.policy(), "goal") + "；" + text(rule, "description")
            + "；收到" + source + "提供的" + text(message, "factId") + "消息，接收版本" + integer(message, "receivedRevision");
        JsonNode known = array(view, "knowledge").get(MessageReactionComponents.index(array(view, "knowledge"), "factId", text(message, "factId")));
        if (view.has("worldTime")) reason += "，消息所述依据年" + integer(known, "asOfYear") + "，本人收到于年" + integer(known, "learnedAtYear");
        ObjectNode proposal = JSON.createObjectNode();
        for (String field : List.of("schemaVersion", "worldId", "requestId", "expectedRevision", "actor")) proposal.set(field, frozen.get(field).deepCopy());
        proposal.put("reason", reason); ArrayNode effects = proposal.putArray("effects");
        Set<String> required = new LinkedHashSet<>(); required.add(text(message, "factId"));
        for (JsonNode condition : array(rule, "conditions")) required.add(text(condition, "factId"));
        for (JsonNode fact : array(rule, "shareFactIds")) {
            required.add(fact.asText()); effects.addObject().put("type", "knowledge.share").put("from", actor).put("to", source).put("factId", fact.asText());
        }
        if (rule.has("relationshipValue")) {
            int relationIndex = MessageReactionComponents.index(array(view, "relationships"), "to", source);
            JsonNode expected = relationIndex < 0 ? NullNode.instance : view.get("relationships").get(relationIndex).get("value");
            ObjectNode relation = effects.addObject().put("type", "relationship.change").put("from", actor).put("to", source);
            relation.set("expectedValue", expected.deepCopy()); relation.put("value", text(rule, "relationshipValue")).put("basis", reason);
        }
        ArrayNode requiresKnown = proposal.putArray("requiresKnown"); required.forEach(requiresKnown::add);
        return canonicalObject(proposal);
    }
}
