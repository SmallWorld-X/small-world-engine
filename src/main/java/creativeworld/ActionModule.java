package creativeworld;

import com.fasterxml.jackson.databind.JsonNode;

/** 行为扩展只收到行动者投影与本次输入；产物仍须通过统一事务校验。 */
public interface ActionModule {
    String id();
    default String version() { return "1"; }
    JsonNode propose(JsonNode actorView, JsonNode input);
}
