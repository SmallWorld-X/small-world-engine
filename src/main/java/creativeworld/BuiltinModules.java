package creativeworld;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import static creativeworld.Protocol.*;

final class BuiltinModules {
    private BuiltinModules() {}
    static ActionModule ask() { return new ActionModule() {
        public String id() { return "ask"; }
        public JsonNode propose(JsonNode view, JsonNode input) {
            object(input, "schemaVersion", "worldId", "requestId", "expectedRevision", "actor", "target", "factId");
            String actor = text(input, "actor"), target = text(input, "target");
            if (!view.path("self").isObject()) throw bad("询问模块需要人物行动者");
            boolean nearby = target.equals(actor);
            for (JsonNode person : view.path("nearby")) nearby |= text(person, "id").equals(target);
            if (!nearby) throw bad("询问对象不在行动者当前可见地点");
            ObjectNode result = proposal(input, "向同地人物询问一项已知消息");
            result.putArray("effects").addObject().put("type", "knowledge.learn").put("personId", actor)
                .put("factId", text(input, "factId")).put("sourcePersonId", target);
            return result;
        }
    }; }
    static ActionModule travel() { return new ActionModule() {
        public String id() { return "travel"; }
        public JsonNode propose(JsonNode view, JsonNode input) {
            object(input, "schemaVersion", "worldId", "requestId", "expectedRevision", "actor", "to");
            if (!view.path("self").isObject()) throw bad("移动模块需要人物行动者");
            ObjectNode result = proposal(input, "人物前往指定的已建立地点");
            result.putArray("effects").addObject().put("type", "person.move").put("personId", text(input, "actor"))
                .put("expectedPlace", text(view.path("self"), "place")).put("to", text(input, "to"));
            return result;
        }
    }; }
    static ActionModule observe() { return new ActionModule() {
        public String id() { return "observe"; }
        public JsonNode propose(JsonNode view, JsonNode input) {
            object(input, "schemaVersion", "worldId", "requestId", "expectedRevision", "actor", "factId");
            if (!view.path("self").isObject()) throw bad("观察模块需要人物行动者");
            ObjectNode result = proposal(input, "人物在当前位置尝试现场观察，是否可见由世界规则裁决");
            result.putArray("effects").addObject().put("type", "knowledge.learn").put("personId", text(input, "actor"))
                .put("factId", text(input, "factId")).put("sourcePersonId", "@world");
            return result;
        }
    }; }
    private static ObjectNode proposal(JsonNode input, String reason) {
        ObjectNode result = JSON.createObjectNode();
        for (String key : new String[]{"schemaVersion", "worldId", "requestId", "expectedRevision", "actor"})
            result.set(key, input.get(key).deepCopy());
        result.put("reason", reason); return result;
    }
}
