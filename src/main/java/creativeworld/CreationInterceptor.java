package creativeworld;

import com.fasterxml.jackson.databind.JsonNode;

/** 拦截器可以抛出异常拒绝构造；修改收到的快照不会改变构造结果。 */
public interface CreationInterceptor {
    default void before(String moduleId, WorldConstructor.Context context) {}
    default void after(String moduleId, WorldConstructor.Context context, JsonNode contribution) {}
}
