package creativeworld;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import static creativeworld.Protocol.*;

/** 只存在于一次锁内读取/执行中的派生状态；持久化文件不保存或信任这份投影。 */
final class WorldState {
    final String worldId, title;
    int revision;
    boolean topologyConfigured;
    final WorldCatalog catalog;
    final WorldTime time;
    final LinkedHashMap<String, ObjectNode> places = new LinkedHashMap<>(), people = new LinkedHashMap<>(), facts = new LinkedHashMap<>();
    final LinkedHashMap<String, ObjectNode> routes = new LinkedHashMap<>();
    final LinkedHashMap<String, LinkedHashMap<String, ObjectNode>> knowledge = new LinkedHashMap<>();
    final LinkedHashMap<String, List<ObjectNode>> beliefs = new LinkedHashMap<>();
    final LinkedHashMap<String, ObjectNode> relationships = new LinkedHashMap<>(), readerKnowledge = new LinkedHashMap<>();

    WorldState(JsonNode seed) {
        object(seed, Set.of("schemaVersion", "worldId", "title", "places", "characters", "facts"), Set.of("relationships", "routes", "entities", "laws", "worldTime"));
        version(seed); worldId = id(seed, "worldId"); title = text(seed, "title");
        time = seed.has("worldTime") ? WorldTime.from(seed.get("worldTime")) : null;
        catalog = new WorldCatalog(seed);
        for (JsonNode place : array(seed, "places")) addPlace(place);
        topologyConfigured = seed.has("routes");
        if (topologyConfigured) for (JsonNode route : array(seed, "routes")) {
            object(route, "from", "to"); addRoute(text(route, "from"), text(route, "to"));
        }
        for (JsonNode person : array(seed, "characters")) addPerson(person);
        for (JsonNode fact : array(seed, "facts")) addFact(fact, 0, "@seed");
        if (seed.has("relationships")) for (JsonNode relation : array(seed, "relationships")) {
            object(relation, Set.of("from", "to", "value"), Set.of("basis"));
            String from = text(relation, "from"), to = text(relation, "to");
            person(from); person(to); text(relation, "value");
            if (relation.has("basis")) text(relation, "basis");
            if (relationships.putIfAbsent(relationKey(from, to), relation.deepCopy()) != null) throw bad("初始关系重复");
            ObjectNode initial = relationships.get(relationKey(from, to)); initial.put("revision", 0);
            if (time != null) initial.put("occurredAtYear", time.year());
        }
        catalog.validateAgainst(places, people, facts);
    }

    private WorldState(WorldState source) {
        worldId = source.worldId; title = source.title; revision = source.revision;
        catalog = source.catalog.copy();
        time = source.time == null ? null : source.time.copy();
        topologyConfigured = source.topologyConfigured; copy(source.routes, routes);
        copy(source.places, places); copy(source.people, people); copy(source.facts, facts);
        copy(source.relationships, relationships); copy(source.readerKnowledge, readerKnowledge);
        source.knowledge.forEach((id, values) -> { LinkedHashMap<String, ObjectNode> next = new LinkedHashMap<>(); copy(values, next); knowledge.put(id, next); });
        source.beliefs.forEach((id, values) -> beliefs.put(id, new ArrayList<>(values.stream().map(ObjectNode::deepCopy).toList())));
    }
    WorldState copy() { return new WorldState(this); }
    private static void copy(Map<String, ObjectNode> from, Map<String, ObjectNode> to) { from.forEach((key, value) -> to.put(key, value.deepCopy())); }

    ObjectNode validateProposal(JsonNode proposal) {
        object(proposal, Set.of("schemaVersion", "worldId", "requestId", "expectedRevision", "actor", "reason", "effects"), Set.of("requiresKnown"));
        version(proposal);
        if (!text(proposal, "worldId").equals(worldId)) throw bad("提案所属世界不一致");
        id(proposal, "requestId"); integer(proposal, "expectedRevision"); text(proposal, "reason");
        String actor = text(proposal, "actor");
        if (!actor.equals("@author")) person(actor);
        if (array(proposal, "effects").isEmpty()) throw bad("提案至少需要一个效果");
        if (proposal.has("requiresKnown")) {
            Set<String> seen = new HashSet<>();
            for (JsonNode required : array(proposal, "requiresKnown")) {
                String factId = text(required, "requiresKnown"); fact(factId);
                if (!seen.add(factId)) throw bad("知情前提重复");
                if (!actor.equals("@author") && !knowledge.get(actor).containsKey(factId)) throw bad("行动者不具备所需知识");
            }
        }
        return canonicalObject(proposal);
    }

    void commit(JsonNode proposal) {
        if (integer(proposal, "expectedRevision") != revision) throw bad("提案版本已过期，不能覆盖当前世界");
        int next = Math.addExact(revision, 1);
        String actor = text(proposal, "actor");
        ArrayNode effects = array(proposal, "effects");
        catalog.checkChangeRequires(actor, effects, facts);
        for (int index = 0; index < effects.size(); index++) {
            JsonNode effect = effects.get(index);
            if (text(effect, "type").equals("time.advance") && index != 0) throw bad("时间推进必须是首效果且一次提案至多一个");
            applyEffect(effect, actor, next);
        }
        catalog.validateAgainst(places, people, facts);
        revision = next;
    }

    private void applyEffect(JsonNode effect, String actor, int next) {
        String type = text(effect, "type");
        switch (type) {
            case "time.advance" -> {
                author(actor);
                if (time == null) throw bad("没有明确年度时钟的世界不能推进时间");
                time.advance(effect);
            }
            case "fact.define" -> {
                object(effect, "type", "fact"); author(actor); addFact(effect.get("fact"), next, "@author");
            }
            case "fact.change" -> {
                object(effect, "type", "factId", "expectedValue", "value");
                ObjectNode target = fact(text(effect, "factId"));
                scalar(effect.get("expectedValue"), "expectedValue"); scalar(effect.get("value"), "value");
                if (!text(target, "mode").equals("state")) throw bad("已确认的固定事实不能修改");
                catalog.checkWriteAccess(text(target, "id"), actor);
                if (!target.get("value").equals(effect.get("expectedValue"))) throw bad("事实旧值不一致");
                target.set("value", effect.get("value").deepCopy());
                if (time != null) target.put("updatedAtYear", time.year());
            }
            case "place.add" -> { object(effect, "type", "place"); author(actor); addPlace(effect.get("place")); }
            case "route.add" -> { object(effect, "type", "from", "to"); author(actor); addRoute(text(effect, "from"), text(effect, "to")); }
            case "person.add" -> { object(effect, "type", "person"); author(actor); addPerson(effect.get("person")); }
            case "group.member.add" -> {
                object(effect, "type", "groupId", "personId"); author(actor);
                String who = id(effect, "personId");
                catalog.addGroupMember(id(effect, "groupId"), who, person(who));
            }
            case "person.move" -> {
                object(effect, "type", "personId", "expectedPlace", "to");
                String who = text(effect, "personId"), to = text(effect, "to"); selfOrAuthor(actor, who);
                ObjectNode target = person(who); place(to);
                String from = text(target, "place");
                if (!from.equals(text(effect, "expectedPlace"))) throw bad("人物旧位置不一致");
                if (topologyConfigured && !from.equals(to) && !routes.containsKey(relationKey(from, to)))
                    throw bad("当前位置到目标地点没有直接路线");
                target.put("place", to);
            }
            case "knowledge.learn" -> {
                object(effect, "type", "personId", "factId", "sourcePersonId");
                String who = text(effect, "personId"), factId = text(effect, "factId"), source = text(effect, "sourcePersonId");
                selfOrAuthor(actor, who); ObjectNode receiver = person(who), target = fact(factId);
                JsonNode value; int asOfYear = time == null ? 0 : time.year();
                if (source.equals("@world")) {
                    boolean observable = false;
                    for (JsonNode location : target.path("observableAt")) observable |= location.asText().equals(text(receiver, "place"));
                    if (!observable) throw bad("当前位置无法观察这项事实");
                    value = target.get("value");
                } else {
                    ObjectNode speaker = person(source);
                    if (!text(speaker, "place").equals(text(receiver, "place"))) throw bad("知识来源与接收者不在同一地点");
                    ObjectNode snapshot = knowledge.get(source).get(factId);
                    if (snapshot == null) throw bad("来源人物并不知道这项事实");
                    value = snapshot.get("value");
                    if (time != null) asOfYear = integer(snapshot, "asOfYear");
                }
                knowledge.get(who).put(factId, snapshot(factId, value, source, next, asOfYear));
            }
            case "knowledge.share" -> {
                object(effect, "type", "from", "to", "factId");
                String from = text(effect, "from"), to = text(effect, "to"), factId = text(effect, "factId");
                selfOrAuthor(actor, from);
                if (from.equals(to)) throw bad("消息转述必须有另一个接收者");
                ObjectNode speaker = person(from), receiver = person(to); fact(factId);
                if (!text(speaker, "place").equals(text(receiver, "place"))) throw bad("消息转述双方不在同一地点");
                ObjectNode known = knowledge.get(from).get(factId);
                if (known == null) throw bad("转述者并不知道这项消息");
                // 传递本人已有认识，不读取当前世界真值；转述不刷新原证据时间。
                int asOfYear = time == null ? 0 : integer(known, "asOfYear");
                knowledge.get(to).put(factId, snapshot(factId, known.get("value"), from, next, asOfYear));
            }
            case "belief.add" -> {
                object(effect, "type", "personId", "text");
                String who = text(effect, "personId"); selfOrAuthor(actor, who); person(who);
                ObjectNode belief = JSON.createObjectNode().put("text", text(effect, "text")).put("revision", next);
                if (time != null) belief.put("occurredAtYear", time.year());
                beliefs.get(who).add(belief);
            }
            case "relationship.change" -> {
                Set<String> fields = Set.of("type", "from", "to", "expectedValue", "value");
                if (time != null) {
                    HashSet<String> required = new HashSet<>(fields); required.add("basis"); object(effect, required, Set.of());
                } else object(effect, fields, Set.of("basis"));
                if (effect.has("basis")) text(effect, "basis");
                String from = text(effect, "from"), to = text(effect, "to"); selfOrAuthor(actor, from);
                person(from); person(to); String key = relationKey(from, to);
                JsonNode expected = effect.get("expectedValue");
                if (!expected.isNull()) text(expected, "expectedValue");
                ObjectNode current = relationships.get(key);
                JsonNode old = current == null ? NullNode.instance : current.get("value");
                if (!old.equals(expected)) throw bad("关系旧值不一致");
                ObjectNode relation = JSON.createObjectNode().put("from", from).put("to", to)
                    .put("value", text(effect, "value")).put("revision", next);
                if (effect.has("basis")) relation.set("basis", effect.get("basis").deepCopy());
                if (time != null) relation.put("occurredAtYear", time.year());
                relationships.put(key, relation);
            }
            case "reader.reveal" -> {
                object(effect, "type", "factId"); author(actor); String factId = text(effect, "factId");
                readerKnowledge.put(factId, snapshot(factId, fact(factId).get("value"), "@author", next));
            }
            default -> throw bad("不支持的效果类型：" + type);
        }
    }

    private void addPlace(JsonNode value) {
        object(value, "id", "name"); String id = id(value, "id"); text(value, "name");
        if (places.containsKey(id) || people.containsKey(id) || catalog.containsEntity(id)) throw bad("人物、地点或实体标识重复");
        places.put(id, canonicalObject(value));
    }
    private void addRoute(String from, String to) {
        place(from); place(to);
        if (from.equals(to)) throw bad("路线不能连接同一地点");
        String key = relationKey(from, to);
        if (routes.containsKey(key)) throw bad("路线已经存在");
        routes.put(key, JSON.createObjectNode().put("from", from).put("to", to));
        topologyConfigured = true;
    }
    private void addPerson(JsonNode value) {
        object(value, Set.of("id", "name", "place", "publicProfile", "privateProfile"), Set.of("birthYear"));
        String id = id(value, "id"); text(value, "name"); place(text(value, "place"));
        if (!value.path("publicProfile").isObject() || !value.path("privateProfile").isObject()) throw bad("人物资料须为对象");
        data(value.path("publicProfile")); data(value.path("privateProfile"));
        if (value.has("birthYear")) {
            if (time == null) throw bad("出生年份需要明确的年度世界时钟");
            if (integer(value, "birthYear") > time.year()) throw bad("出生年份不能晚于当前世界年份");
        }
        if (time != null) for (String field : List.of("publicProfile", "privateProfile")) {
            JsonNode profile = value.get(field);
            if (profile.has("age") || profile.has("birthYear")) throw bad("年度世界的人物年龄与出生年份只能使用统一来源");
        }
        if (people.containsKey(id) || places.containsKey(id) || catalog.containsEntity(id)) throw bad("人物、地点或实体标识重复");
        ObjectNode person = canonicalObject(value);
        if (time != null) person.put("publicProfileAsOfYear", time.year()).put("privateProfileAsOfYear", time.year());
        people.put(id, person); knowledge.put(id, new LinkedHashMap<>()); beliefs.put(id, new ArrayList<>());
    }
    private void addFact(JsonNode value, int next, String source) {
        object(value, Set.of("id", "subject", "predicate", "value", "mode", "knownBy"), Set.of("observableAt"));
        String id = id(value, "id"), subject = text(value, "subject"), predicate = text(value, "predicate"), mode = text(value, "mode");
        scalar(value.get("value"), "value");
        if (!Set.of("fixed", "state").contains(mode)) throw bad("事实模式不支持");
        if (facts.containsKey(id)) throw bad("事实标识重复");
        for (ObjectNode existing : facts.values())
            if (text(existing, "subject").equals(subject) && text(existing, "predicate").equals(predicate)) throw bad("同一主体与谓词的事实重复");
        Set<String> known = references(array(value, "knownBy"), people, "知情人物");
        if (value.has("observableAt")) references(array(value, "observableAt"), places, "可观察地点");
        facts.put(id, canonicalObject(value));
        if (time != null && mode.equals("state")) facts.get(id).put("updatedAtYear", time.year());
        for (String who : known) knowledge.get(who).put(id, snapshot(id, value.get("value"), source, next));
    }
    private static Set<String> references(ArrayNode values, Map<String, ObjectNode> targets, String label) {
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode value : values) {
            String id = text(value, label);
            if (!targets.containsKey(id)) throw bad(label + "不存在");
            if (!seen.add(id)) throw bad(label + "重复");
        }
        return seen;
    }
    private ObjectNode person(String id) { ObjectNode value = people.get(id); if (value == null) throw bad("人物不存在"); return value; }
    private ObjectNode place(String id) { ObjectNode value = places.get(id); if (value == null) throw bad("地点不存在"); return value; }
    private ObjectNode fact(String id) { ObjectNode value = facts.get(id); if (value == null) throw bad("事实不存在"); return value; }
    private static void author(String actor) { if (!actor.equals("@author")) throw bad("此效果仅允许作者执行"); }
    private static void selfOrAuthor(String actor, String who) { if (!actor.equals("@author") && !actor.equals(who)) throw bad("行动者不能代替他人执行此效果"); }
    private static String relationKey(String from, String to) { return from.length() + ":" + from + to; }
    private ObjectNode snapshot(String factId, JsonNode value, String source, int revision) {
        return snapshot(factId, value, source, revision, time == null ? 0 : time.year());
    }
    private ObjectNode snapshot(String factId, JsonNode value, String source, int revision, int asOfYear) {
        ObjectNode result = JSON.createObjectNode().put("factId", factId).put("source", source).put("revision", revision);
        if (time != null) result.put("learnedAtYear", time.year()).put("asOfYear", asOfYear);
        result.set("value", value.deepCopy()); return result;
    }

    ObjectNode metadata() {
        ObjectNode result = JSON.createObjectNode().put("schemaVersion", 1).put("worldId", worldId).put("title", title).put("revision", revision);
        if (time != null) result.set("worldTime", time.json());
        return result;
    }
    ObjectNode inspect() {
        ObjectNode result = metadata();
        result.putObject("counts").put("places", places.size()).put("characters", people.size()).put("facts", facts.size())
            .put("routes", routes.size())
            .put("entities", catalog.entityCount()).put("laws", catalog.lawCount())
            .put("knowledge", knowledge.values().stream().mapToInt(Map::size).sum())
            .put("beliefs", beliefs.values().stream().mapToInt(List::size).sum())
            .put("relationships", relationships.size()).put("readerKnowledge", readerKnowledge.size());
        return result;
    }
    ObjectNode view(String actor) {
        ObjectNode result = metadata().put("actor", actor);
        if (actor.equals("@reader")) { result.set("readerKnowledge", JSON.valueToTree(readerKnowledge.values())); return result; }
        if (actor.equals("@author")) {
            result.set("places", JSON.valueToTree(places.values()));
            ArrayNode characters = result.putArray("characters"); people.values().forEach(person -> characters.add(personView(person)));
            if (topologyConfigured) result.set("routes", JSON.valueToTree(routes.values()));
            result.set("entities", catalog.entities()); result.set("laws", catalog.laws());
            result.set("facts", JSON.valueToTree(facts.values()));
            ObjectNode visibleKnowledge = result.putObject("knowledge");
            knowledge.forEach((who, values) -> visibleKnowledge.set(who, JSON.valueToTree(values.values())));
            result.set("beliefs", JSON.valueToTree(beliefs)); result.set("relationships", JSON.valueToTree(relationships.values()));
            result.set("readerKnowledge", JSON.valueToTree(readerKnowledge.values())); return result;
        }
        ObjectNode self = person(actor); result.set("self", personView(self));
        ArrayNode nearby = result.putArray("nearby");
        for (ObjectNode other : people.values()) if (!text(other, "id").equals(actor) && text(other, "place").equals(text(self, "place"))) {
            ObjectNode visible = nearby.addObject().put("id", text(other, "id")).put("name", text(other, "name")).put("place", text(other, "place"));
            visible.set("publicProfile", other.get("publicProfile").deepCopy());
            if (time != null) {
                age(visible, other); visible.put("publicProfileAsOfYear", integer(other, "publicProfileAsOfYear"))
                    .put("publicProfileStatus", profileStatus(other, "publicProfileAsOfYear"));
            }
        }
        result.set("knowledge", JSON.valueToTree(knowledge.get(actor).values())); result.set("beliefs", JSON.valueToTree(beliefs.get(actor)));
        ArrayNode visibleRelations = result.putArray("relationships");
        for (ObjectNode relation : relationships.values()) if (text(relation, "from").equals(actor)) {
            ObjectNode visible = visibleRelations.addObject().put("to", text(relation, "to")).put("value", text(relation, "value")).put("revision", integer(relation, "revision"));
            if (time != null) {
                visible.put("occurredAtYear", integer(relation, "occurredAtYear"));
                if (relation.has("basis")) visible.set("basis", relation.get("basis").deepCopy());
            }
        }
        return result;
    }
    private ObjectNode personView(ObjectNode person) {
        ObjectNode result = person.deepCopy();
        if (time != null) {
            age(result, person);
            result.put("publicProfileStatus", profileStatus(person, "publicProfileAsOfYear"))
                .put("privateProfileStatus", profileStatus(person, "privateProfileAsOfYear"));
        }
        return result;
    }
    private void age(ObjectNode projected, ObjectNode person) {
        if (person.has("birthYear")) projected.put("age", time.year() - integer(person, "birthYear"));
        else projected.putNull("age");
    }
    private String profileStatus(ObjectNode person, String field) {
        return integer(person, field) == time.year() ? "current" : "historical";
    }
}
