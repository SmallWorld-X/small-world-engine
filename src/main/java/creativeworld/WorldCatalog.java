package creativeworld;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.math.BigDecimal;
import java.util.*;
import static creativeworld.Protocol.*;

/** 世界目录与显式法则；不从说明文字推断规则，成员增量由正式事件登记。 */
public final class WorldCatalog {
    private final LinkedHashMap<String, ObjectNode> entities = new LinkedHashMap<>(), laws = new LinkedHashMap<>();

    public WorldCatalog(JsonNode seed) {
        if (seed == null || !seed.isObject()) throw bad("世界目录来源须为对象");
        if (seed.has("entities")) for (JsonNode entity : array(seed, "entities")) {
            object(entity, "id", "kind", "name", "attributes", "refs");
            String entityId = id(entity, "id"); text(entity, "name");
            if (!Set.of("region", "resource", "faction", "group").contains(text(entity, "kind"))) throw bad("实体种类不支持");
            if (!entity.path("attributes").isObject()) throw bad("实体属性须为对象");
            data(entity.get("attributes"));
            Set<String> seen = new HashSet<>();
            for (JsonNode ref : array(entity, "refs"))
                if (!seen.add(id(ref, "refs"))) throw bad("实体引用重复");
            if (entities.putIfAbsent(entityId, canonicalObject(entity)) != null) throw bad("实体标识重复");
        }
        Set<String> writeProtected = new HashSet<>();
        if (seed.has("laws")) for (JsonNode law : array(seed, "laws")) {
            String kind = text(law, "kind");
            switch (kind) {
                case "range" -> {
                    object(law, Set.of("id", "kind", "factId"), Set.of("min", "max"));
                    if (!law.has("min") && !law.has("max")) throw bad("范围法则至少需要一个边界");
                    BigDecimal min = law.has("min") ? number(law.get("min"), "min") : null;
                    BigDecimal max = law.has("max") ? number(law.get("max"), "max") : null;
                    if (min != null && max != null && min.compareTo(max) > 0) throw bad("范围法则下界不能大于上界");
                }
                case "oneOf" -> {
                    object(law, "id", "kind", "factId", "values");
                    if (array(law, "values").isEmpty()) throw bad("枚举法则至少需要一个值");
                    Set<JsonNode> seen = new HashSet<>();
                    for (JsonNode value : array(law, "values")) {
                        scalar(value, "values");
                        if (!seen.add(canonical(value))) throw bad("枚举法则的值重复");
                    }
                }
                case "writeAccess" -> {
                    object(law, "id", "kind", "factId", "actors");
                    if (!writeProtected.add(id(law, "factId"))) throw bad("同一事实只能声明一条写权限法则");
                    Set<String> seen = new HashSet<>();
                    for (JsonNode actor : array(law, "actors"))
                        if (!seen.add(id(actor, "actors"))) throw bad("写权限人物重复");
                }
                case "changeRequires" -> {
                    object(law, Set.of("id", "kind", "factId", "conditions"), Set.of("whenEquals", "actorCondition"));
                    if (array(law, "conditions").isEmpty()) throw bad("变化前提法则至少需要一个条件");
                    Set<String> seen = new HashSet<>();
                    for (JsonNode condition : array(law, "conditions")) {
                        object(condition, "factId", "equals");
                        if (!seen.add(id(condition, "factId"))) throw bad("变化前提引用的事实重复");
                        equalityValue(condition.get("equals"), "equals");
                    }
                    if (law.has("whenEquals")) equalityValue(law.get("whenEquals"), "whenEquals");
                    if (law.has("actorCondition")) {
                        JsonNode condition = object(law.get("actorCondition"), "predicate", "equals");
                        text(condition, "predicate"); equalityValue(condition.get("equals"), "equals");
                    }
                }
                default -> throw bad("法则种类不支持");
            }
            String lawId = id(law, "id"); id(law, "factId");
            if (laws.putIfAbsent(lawId, canonicalObject(law)) != null) throw bad("法则标识重复");
        }
    }

    private WorldCatalog(WorldCatalog source) {
        source.entities.forEach((key, value) -> entities.put(key, value.deepCopy()));
        source.laws.forEach((key, value) -> laws.put(key, value.deepCopy()));
    }
    WorldCatalog copy() { return new WorldCatalog(this); }
    boolean containsEntity(String id) { return entities.containsKey(id); }
    public int entityCount() { return entities.size(); }
    public int lawCount() { return laws.size(); }
    public ArrayNode entities() { return JSON.valueToTree(entities.values()); }
    public ArrayNode laws() { return JSON.valueToTree(laws.values()); }

    void checkWriteAccess(String factId, String actor) {
        if (actor.equals("@author")) return;
        for (ObjectNode law : laws.values()) {
            if (!text(law, "kind").equals("writeAccess") || !text(law, "factId").equals(factId)) continue;
            for (JsonNode allowed : array(law, "actors")) if (allowed.asText().equals(actor)) return;
            throw bad("行动者没有修改这项状态的权限：" + factId);
        }
        // 未声明权限的旧资料保留原语义；不能把兼容模式解释成已验证的业务授权。
    }

    /** 只检查整笔提交开始时的事实；不能靠本次先改前提再使用取得资格。 */
    void checkChangeRequires(String actor, ArrayNode effects, Map<String, ? extends JsonNode> facts) {
        for (JsonNode effect : effects) {
            if (!text(effect, "type").equals("fact.change")) continue;
            String factId = text(effect, "factId");
            for (ObjectNode law : laws.values()) {
                if (!text(law, "kind").equals("changeRequires") || !text(law, "factId").equals(factId)) continue;
                JsonNode newValue = scalar(effect.get("value"), "value");
                if (law.has("whenEquals") && !canonical(law.get("whenEquals")).equals(canonical(newValue))) continue;
                for (JsonNode condition : array(law, "conditions")) {
                    JsonNode source = referencedFact(facts, text(condition, "factId"));
                    if (!matches(source, condition.get("equals"))) throw bad("状态变化不满足声明的执行前提");
                }
                if (law.has("actorCondition")) {
                    JsonNode condition = law.get("actorCondition");
                    String predicate = text(condition, "predicate");
                    boolean qualifies = false;
                    for (JsonNode source : facts.values()) {
                        if (text(source, "subject").equals(actor) && text(source, "predicate").equals(predicate)) {
                            qualifies = matches(source, condition.get("equals")); break;
                        }
                    }
                    if (!qualifies) throw bad("状态变化不满足声明的执行前提");
                }
            }
        }
    }

    void addGroupMember(String groupId, String personId, JsonNode person) {
        ObjectNode group = entities.get(groupId);
        if (group == null || !text(group, "kind").equals("group")) throw bad("成员登记的群体不存在");
        if (!id(person.path("publicProfile"), "groupId").equals(groupId)) throw bad("人物所属群体与登记目标不一致");
        ArrayNode refs = array(group, "refs");
        for (JsonNode ref : refs) if (ref.asText().equals(personId)) throw bad("群体已经登记该成员");
        refs.add(personId);
    }

    /** 全部资料读取完成后检查引用；事务结束后按当前事实检查法则。 */
    public void validateAgainst(Map<String, ? extends JsonNode> places, Map<String, ? extends JsonNode> people,
                                Map<String, ? extends JsonNode> facts) {
        for (Map.Entry<String, ObjectNode> entry : entities.entrySet()) {
            String entityId = entry.getKey();
            ObjectNode entity = entry.getValue();
            if (places.containsKey(entityId) || people.containsKey(entityId)) throw bad("人物、地点或实体标识重复");
            int members = 0;
            for (JsonNode ref : array(entity, "refs")) {
                String target = ref.asText();
                if (!places.containsKey(target) && !people.containsKey(target) && !entities.containsKey(target))
                    throw bad("实体引用不存在：" + target);
                if (people.containsKey(target)) members++;
            }
            JsonNode attributes = entity.get("attributes");
            if (text(entity, "kind").equals("group")) {
                JsonNode population = referencedFact(facts, id(attributes, "populationFactId"));
                JsonNode value = scalar(population.get("value"), "群体人数");
                if (!text(population, "mode").equals("state") || !value.isIntegralNumber() || value.bigIntegerValue().signum() < 0)
                    throw bad("群体人数必须引用非负整数状态事实");
                if (value.bigIntegerValue().compareTo(java.math.BigInteger.valueOf(members)) < 0)
                    throw bad("群体人数不能少于已展开成员数量");
            }
            if (text(entity, "kind").equals("resource") && attributes.has("quantityFactId")) {
                JsonNode quantity = referencedFact(facts, id(attributes, "quantityFactId"));
                if (number(quantity.get("value"), "资源数量").signum() < 0) throw bad("资源数量不能为负数");
            }
        }
        for (Map.Entry<String, ? extends JsonNode> entry : people.entrySet()) {
            JsonNode profile = entry.getValue().path("publicProfile");
            if (!profile.has("groupId")) continue;
            String groupId = id(profile, "groupId"); ObjectNode group = entities.get(groupId);
            if (group == null || !text(group, "kind").equals("group")) throw bad("人物所属群体不存在");
            boolean member = false;
            for (JsonNode ref : array(group, "refs")) member |= ref.asText().equals(entry.getKey());
            if (!member) throw bad("人物所属群体未登记该成员");
        }
        for (ObjectNode law : laws.values()) {
            String factId = text(law, "factId"); JsonNode fact = referencedFact(facts, factId);
            JsonNode value = scalar(fact.get("value"), "事实值");
            if (text(law, "kind").equals("range")) {
                BigDecimal current = number(value, "范围法则事实值");
                if ((law.has("min") && current.compareTo(number(law.get("min"), "min")) < 0)
                    || (law.has("max") && current.compareTo(number(law.get("max"), "max")) > 0))
                    throw bad("当前事实值违反范围法则：" + text(law, "id"));
            } else if (text(law, "kind").equals("oneOf")) {
                JsonNode current = canonical(value); boolean allowed = false;
                for (JsonNode option : array(law, "values")) allowed |= current.equals(option);
                if (!allowed) throw bad("当前事实值违反枚举法则：" + text(law, "id"));
            } else if (text(law, "kind").equals("writeAccess")) {
                if (!text(fact, "mode").equals("state")) throw bad("写权限必须对应可变状态事实");
                for (JsonNode actor : array(law, "actors"))
                    if (!people.containsKey(actor.asText())) throw bad("写权限人物不存在：" + actor.asText());
            } else if (text(law, "kind").equals("changeRequires")) {
                if (!text(fact, "mode").equals("state")) throw bad("变化前提必须对应可变状态事实");
                for (JsonNode condition : array(law, "conditions")) referencedFact(facts, text(condition, "factId"));
            }
        }
    }

    private static JsonNode equalityValue(JsonNode value, String label) {
        scalar(value, label);
        if (value.isNull() || (value.isTextual() && value.asText().isBlank()))
            throw bad("变化前提比较值不能是未知或空白文本");
        return value;
    }

    private static boolean matches(JsonNode fact, JsonNode expected) {
        return canonical(fact.get("value")).equals(canonical(expected));
    }

    private static JsonNode referencedFact(Map<String, ? extends JsonNode> facts, String factId) {
        JsonNode fact = facts.get(factId);
        if (fact == null) throw bad("目录引用的事实不存在：" + factId);
        return fact;
    }

    private static BigDecimal number(JsonNode value, String label) {
        scalar(value, label);
        if (!value.isNumber()) throw bad("需要有限数字：" + label);
        return value.decimalValue();
    }
}
