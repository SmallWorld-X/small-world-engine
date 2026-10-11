package creativeworld;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import static creativeworld.Protocol.*;

/** 将明确的结构化蓝图编成可回查草案；不解释自然语言，也不写入世界文件。 */
public final class WorldGenesis {
    private static final Set<String> SEED_ARRAYS = Set.of("places", "routes", "characters", "facts", "relationships", "entities", "laws");
    private static final Set<String> CONTRIBUTION_FIELDS;
    static {
        Set<String> fields = new HashSet<>(SEED_ARRAYS);
        fields.addAll(Set.of("worldTime", "privateProfileAdds", "entityRefsAdds", "notes", "unresolved"));
        CONTRIBUTION_FIELDS = Set.copyOf(fields);
    }

    private final LinkedHashMap<ConstructorKey, ConstructorBinding> constructors = new LinkedHashMap<>();
    private final List<CreationInterceptor> interceptors = new ArrayList<>();

    public WorldGenesis() { this(true); }

    public WorldGenesis(boolean includeDefaults) {
        if (!includeDefaults) return;
        register(new Builtin("world", "world"));
        register(new Builtin("person", "people", "world"));
        register(new Builtin("faction", "factions", "world"));
        register(new Builtin("group", "groups", "world", "person", "faction"));
        register(new Builtin("extra", "extras", "group", "person"));
        register(new Builtin("relationship", "relationships", "person", "extra"));
        register(new Builtin("emotion", "emotions", "person", "extra"));
        register(new Builtin("law", "laws", "world"));
    }

    public synchronized WorldGenesis register(WorldConstructor constructor) {
        Objects.requireNonNull(constructor, "构造器不能为空");
        ConstructorBinding binding = new ConstructorBinding(name(constructor.id(), "构造器标识"),
            moduleVersion(constructor.version()), name(constructor.sectionName(), "构造资料名称"),
            dependencies(constructor), constructor);
        ConstructorKey key = new ConstructorKey(binding.id, binding.version);
        if (constructors.putIfAbsent(key, binding) != null) throw bad("构造器标识与版本重复：" + binding.id + " / " + binding.version);
        return this;
    }

    public synchronized WorldGenesis intercept(CreationInterceptor interceptor) {
        interceptors.add(Objects.requireNonNull(interceptor, "构造拦截器不能为空"));
        return this;
    }

    public synchronized ObjectNode build(JsonNode input) {
        ObjectNode blueprint = canonicalObject(object(input, Set.of("schemaVersion", "worldId", "title", "idea", "sections"),
            Set.of("unresolved", "constructors")));
        List<ConstructorBinding> order = ordered(blueprint, new LinkedHashMap<>(constructors));
        validateBlueprint(blueprint, order);
        validateBindings(order);
        boolean explicitVersions = blueprint.has("constructors");
        List<CreationInterceptor> checks = List.copyOf(interceptors);
        ObjectNode seed = JSON.createObjectNode().put("schemaVersion", 1)
            .put("worldId", text(blueprint, "worldId")).put("title", text(blueprint, "title"));
        for (String field : List.of("places", "characters", "facts", "relationships", "entities", "laws")) seed.putArray(field);
        ObjectNode construction = JSON.createObjectNode();
        ArrayNode moduleOrder = construction.putArray("moduleOrder"), modules = construction.putArray("modules");
        ArrayNode evidence = construction.putArray("evidence"), unresolved = construction.putArray("unresolved");
        if (blueprint.has("unresolved")) append(unresolved, array(blueprint, "unresolved"));
        construction.putArray("notes").add("idea仅保留创作目标；本次构造直接处理结构化资料，未进行自然语言理解或人物心理推导。");

        for (ConstructorBinding binding : order) {
            String moduleId = binding.id, sectionName = binding.section;
            for (CreationInterceptor check : checks) {
                validateBindings(order);
                check.before(moduleId, new WorldConstructor.Context(blueprint, seed, sectionName));
                validateBindings(order);
            }
            validateBindings(order);
            ObjectNode output = binding.constructor.construct(new WorldConstructor.Context(blueprint, seed, sectionName));
            validateBindings(order);
            if (output == null) throw bad("构造器未返回贡献：" + moduleId);
            ObjectNode contribution = canonicalObject(output);
            validateContribution(contribution);
            for (CreationInterceptor check : checks) {
                validateBindings(order);
                check.after(moduleId, new WorldConstructor.Context(blueprint, seed, sectionName), contribution.deepCopy());
                validateBindings(order);
            }
            ObjectNode counts = merge(seed, contribution);
            moduleOrder.add(moduleId);
            ObjectNode record = modules.addObject().put("id", moduleId);
            if (explicitVersions) {
                record.put("version", binding.version).put("section", sectionName);
                ArrayNode dependencies = record.putArray("dependencies"); new TreeSet<>(binding.dependencies).forEach(dependencies::add);
            }
            record.set("outputs", counts);
            record.set("notes", contribution.has("notes") ? contribution.get("notes").deepCopy() : JSON.createArrayNode());
            ObjectNode source = evidence.addObject().put("moduleId", moduleId).put("section", sectionName).put("method", "structured-input");
            source.set("outputs", counts.deepCopy());
            if (explicitVersions) source.put("moduleVersion", binding.version);
            if (contribution.has("unresolved")) append(unresolved, array(contribution, "unresolved"));
        }

        // 必须走正式内核的初始资料校验；草案仍不写正式存档。
        new WorldState(seed);
        ObjectNode result = JSON.createObjectNode().put("schemaVersion", 1).put("kind", "world-draft");
        result.set("blueprint", blueprint); result.set("seed", canonicalObject(seed)); result.set("construction", construction);
        return canonicalObject(result);
    }

    public synchronized ObjectNode confirmSeed(JsonNode draft) {
        ObjectNode frozen = canonicalObject(object(draft, "schemaVersion", "kind", "blueprint", "seed", "construction"));
        version(frozen);
        if (!text(frozen, "kind").equals("world-draft")) throw bad("确认输入不是世界草案");
        ObjectNode rebuilt = build(frozen.get("blueprint"));
        if (!frozen.equals(rebuilt)) throw bad("世界草案与原蓝图的重新构造结果不一致");
        return ((ObjectNode) rebuilt.get("seed")).deepCopy();
    }

    private static void validateBlueprint(JsonNode input, List<ConstructorBinding> selected) {
        version(input); id(input, "worldId"); text(input, "title"); text(input, "idea");
        JsonNode sections = input.get("sections");
        if (!sections.isObject()) throw bad("sections必须是对象");
        Set<String> supported = new HashSet<>();
        for (ConstructorBinding binding : selected)
            if (!supported.add(binding.section)) throw bad("已选构造资料名称重复：" + binding.section);
        if (!supported.contains("world")) throw bad("已选构造器缺少world资料处理器");
        object(sections, Set.of("world"), supported);
        sections.forEach(section -> { if (!section.isObject() && !section.isArray()) throw bad("构造资料必须是对象或数组"); data(section); });
        if (input.has("unresolved")) validateMessages(array(input, "unresolved"), false);
    }

    private static List<ConstructorBinding> ordered(ObjectNode blueprint, Map<ConstructorKey, ConstructorBinding> registry) {
        LinkedHashMap<String, ConstructorBinding> selected = new LinkedHashMap<>();
        if (blueprint.has("constructors")) {
            for (JsonNode row : array(blueprint, "constructors")) {
                object(row, "id", "version");
                String id = name(text(row, "id"), "构造器标识"), version = moduleVersion(text(row, "version"));
                if (selected.containsKey(id)) throw bad("一次构造只能选择同标识的一个版本：" + id);
                ConstructorBinding binding = registry.get(new ConstructorKey(id, version));
                if (binding == null) throw bad("构造器版本未注册：" + id + " / " + version);
                selected.put(id, binding);
            }
        } else {
            for (ConstructorKey key : registry.keySet()) {
                if (selected.containsKey(key.id)) continue;
                ConstructorBinding binding = registry.get(new ConstructorKey(key.id, "1"));
                if (binding == null) throw bad("旧蓝图需要构造器版本1：" + key.id);
                selected.put(key.id, binding);
            }
        }
        List<ConstructorBinding> result = new ArrayList<>();
        Map<String, Integer> visits = new HashMap<>();
        for (String id : selected.keySet()) visit(id, selected, visits, result);
        return List.copyOf(result);
    }

    private static void visit(String id, Map<String, ConstructorBinding> selected, Map<String, Integer> visits, List<ConstructorBinding> result) {
        int state = visits.getOrDefault(id, 0);
        if (state == 2) return;
        if (state == 1) throw bad("构造器依赖存在循环：" + id);
        ConstructorBinding binding = selected.get(id);
        if (binding == null) throw bad("未选择构造器依赖：" + id);
        visits.put(id, 1);
        for (String dependency : new TreeSet<>(binding.dependencies)) visit(dependency, selected, visits, result);
        visits.put(id, 2); result.add(binding);
    }

    private static Set<String> dependencies(WorldConstructor constructor) {
        Set<String> declared = constructor.dependencies();
        if (declared == null) throw bad("构造器依赖不能为空");
        Set<String> result = new HashSet<>();
        for (String dependency : declared) result.add(name(dependency, "依赖标识"));
        return Set.copyOf(result);
    }

    private static void validateBindings(List<ConstructorBinding> bindings) {
        for (ConstructorBinding binding : bindings) {
            WorldConstructor constructor = binding.constructor;
            if (!binding.id.equals(constructor.id()) || !binding.version.equals(moduleVersion(constructor.version()))
                || !binding.section.equals(constructor.sectionName()) || !binding.dependencies.equals(dependencies(constructor)))
                throw bad("构造器在注册后改变了标识、版本、资料名称或依赖：" + binding.id);
        }
    }

    private record ConstructorKey(String id, String version) {}
    private record ConstructorBinding(String id, String version, String section, Set<String> dependencies, WorldConstructor constructor) {}

    private static String name(String value, String label) {
        if (value == null || value.isBlank() || value.startsWith("@")) throw bad(label + "无效");
        return value;
    }

    private static void validateContribution(ObjectNode contribution) {
        object(contribution, Set.of(), CONTRIBUTION_FIELDS);
        for (String key : SEED_ARRAYS) if (contribution.has(key)) array(contribution, key);
        if (contribution.has("worldTime")) validateWorldTime(contribution.get("worldTime"));
        if (contribution.has("notes")) validateMessages(array(contribution, "notes"), true);
        if (contribution.has("unresolved")) validateMessages(array(contribution, "unresolved"), false);
        if (contribution.has("privateProfileAdds")) for (JsonNode addition : array(contribution, "privateProfileAdds")) {
            object(addition, "personId", "fields"); id(addition, "personId");
            if (!addition.path("fields").isObject()) throw bad("人物私有资料补充必须是对象");
            data(addition.get("fields"));
        }
        if (contribution.has("entityRefsAdds")) for (JsonNode addition : array(contribution, "entityRefsAdds")) {
            object(addition, "entityId", "refs"); id(addition, "entityId");
            validateReferences(array(addition, "refs"));
        }
    }

    private static void validateMessages(ArrayNode messages, boolean textOnly) {
        for (JsonNode message : messages) {
            if (message.isTextual()) text(message, "说明");
            else if (!textOnly && message.isObject()) data(message);
            else throw bad("说明须为非空文本" + (textOnly ? "" : "或对象"));
        }
    }

    private static void validateReferences(ArrayNode refs) {
        Set<String> seen = new HashSet<>();
        for (JsonNode ref : refs) {
            String value = name(text(ref, "引用"), "引用");
            if (!seen.add(value)) throw bad("引用重复：" + value);
        }
    }

    private static void validateWorldTime(JsonNode value) {
        WorldTime.from(value);
    }

    private static ObjectNode merge(ObjectNode seed, ObjectNode contribution) {
        ObjectNode counts = JSON.createObjectNode();
        if (contribution.has("worldTime")) {
            if (seed.has("worldTime")) throw bad("构造器不能重复贡献共同时间");
            seed.set("worldTime", contribution.get("worldTime").deepCopy());
            counts.put("worldTime", 1);
        }
        for (String field : List.of("places", "routes", "characters", "facts", "relationships", "entities", "laws")) {
            if (!contribution.has(field)) continue;
            ArrayNode values = array(contribution, field);
            ArrayNode target = seed.has(field) ? array(seed, field) : seed.putArray(field);
            append(target, values); counts.put(field, values.size());
        }
        if (contribution.has("privateProfileAdds")) {
            for (JsonNode addition : array(contribution, "privateProfileAdds")) {
                ObjectNode person = find(array(seed, "characters"), text(addition, "personId"), "人物");
                ObjectNode profile = (ObjectNode) person.get("privateProfile");
                JsonNode fields = addition.get("fields");
                fields.fieldNames().forEachRemaining(field -> {
                    if (profile.has(field)) throw bad("构造器不能覆盖人物已有私有资料：" + field);
                    profile.set(field, fields.get(field).deepCopy());
                });
            }
            counts.put("privateProfileAdds", array(contribution, "privateProfileAdds").size());
        }
        if (contribution.has("entityRefsAdds")) {
            for (JsonNode addition : array(contribution, "entityRefsAdds")) {
                ObjectNode entity = find(array(seed, "entities"), text(addition, "entityId"), "实体");
                ArrayNode refs = array(entity, "refs");
                Set<String> seen = new HashSet<>(); refs.forEach(ref -> seen.add(ref.asText()));
                for (JsonNode ref : array(addition, "refs")) if (seen.add(ref.asText())) refs.add(ref.deepCopy());
            }
            counts.put("entityRefsAdds", array(contribution, "entityRefsAdds").size());
        }
        return counts;
    }

    private static ObjectNode find(ArrayNode values, String id, String label) {
        for (JsonNode value : values) if (value.path("id").asText().equals(id)) return (ObjectNode) value;
        throw bad(label + "不存在：" + id);
    }

    private static void append(ArrayNode target, ArrayNode values) { values.forEach(value -> target.add(value.deepCopy())); }

    private static final class Builtin implements WorldConstructor {
        private final String id, section;
        private final Set<String> dependencies;

        Builtin(String id, String section, String... dependencies) {
            this.id = id; this.section = section; this.dependencies = Set.of(dependencies);
        }
        public String id() { return id; }
        public String sectionName() { return section; }
        public Set<String> dependencies() { return dependencies; }

        public ObjectNode construct(Context context) {
            ObjectNode result = JSON.createObjectNode();
            JsonNode input = context.section();
            if (input.isMissingNode()) {
                result.putArray("notes").add("未提供" + section + "资料，本模块不生成设定。");
                return result;
            }
            switch (id) {
                case "world" -> world(input, result);
                case "person" -> people(input, result);
                case "relationship" -> relationships(input, result);
                case "emotion" -> emotions(input, context.seed(), result);
                case "law" -> result.set("laws", rows(input, section).deepCopy());
                case "faction" -> entities(input, "faction", result);
                case "group" -> groups(input, context.seed(), result);
                case "extra" -> extras(input, context.seed(), result);
                default -> throw bad("内置构造器不支持：" + id);
            }
            ArrayNode notes = result.has("notes") ? array(result, "notes") : result.putArray("notes");
            notes.add("根据" + section + "明确资料构造；未补造未声明的经历或未来结果。");
            return result;
        }

        private static ArrayNode rows(JsonNode input, String section) {
            if (!input.isArray()) throw bad(section + "须为数组");
            return (ArrayNode) input;
        }

        private static void world(JsonNode input, ObjectNode result) {
            object(input, Set.of("places"), Set.of("worldTime", "routes", "facts", "entities"));
            result.set("places", array(input, "places").deepCopy());
            if (input.has("worldTime")) result.set("worldTime", input.get("worldTime").deepCopy());
            if (input.has("routes")) result.set("routes", array(input, "routes").deepCopy());
            if (input.has("facts")) result.set("facts", array(input, "facts").deepCopy());
            if (input.has("entities")) {
                ArrayNode values = array(input, "entities");
                for (JsonNode entity : values) {
                    object(entity, "id", "kind", "name", "attributes", "refs");
                    if (!Set.of("region", "resource").contains(text(entity, "kind"))) throw bad("world.entities只接受region或resource");
                }
                result.set("entities", values.deepCopy());
            }
        }

        private static void people(JsonNode input, ObjectNode result) {
            ArrayNode values = rows(input, "people");
            for (JsonNode person : values) validatePerson(person);
            result.set("characters", values.deepCopy());
        }

        private static void validatePerson(JsonNode person) {
            object(person, Set.of("id", "name", "place", "publicProfile", "privateProfile"), Set.of("birthYear"));
            Protocol.id(person, "id"); text(person, "name"); text(person, "place");
            if (person.has("birthYear")) integer(person, "birthYear");
            if (!person.path("publicProfile").isObject() || !person.path("privateProfile").isObject()) throw bad("人物资料须为对象");
            data(person.get("publicProfile")); data(person.get("privateProfile"));
        }

        private static void relationships(JsonNode input, ObjectNode result) {
            ArrayNode values = result.putArray("relationships"), notes = result.putArray("notes");
            for (JsonNode relation : rows(input, "relationships")) {
                object(relation, Set.of("from", "to", "value"), Set.of("basis"));
                ObjectNode compiled = values.addObject().put("from", text(relation, "from"))
                    .put("to", text(relation, "to")).put("value", text(relation, "value"));
                if (relation.has("basis")) {
                    notes.add("关系依据：" + text(relation, "from") + "→" + text(relation, "to") + "；" + text(relation, "basis"));
                    compiled.set("basis", relation.get("basis").deepCopy());
                }
            }
        }

        private static void emotions(JsonNode input, ObjectNode seed, ObjectNode result) {
            LinkedHashMap<String, ArrayNode> byPerson = new LinkedHashMap<>();
            for (JsonNode emotion : rows(input, "emotions")) {
                object(emotion, "personId", "feeling", "trigger");
                String who = Protocol.id(emotion, "personId"); find(array(seed, "characters"), who, "情感所属人物");
                byPerson.computeIfAbsent(who, ignored -> JSON.createArrayNode()).addObject()
                    .put("feeling", text(emotion, "feeling")).put("trigger", text(emotion, "trigger"));
            }
            ArrayNode additions = result.putArray("privateProfileAdds");
            byPerson.forEach((who, feelings) -> additions.addObject().put("personId", who).putObject("fields").set("initialEmotions", feelings));
        }

        private static void entities(JsonNode input, String kind, ObjectNode result) {
            ArrayNode values = result.putArray("entities");
            for (JsonNode entity : rows(input, kind + "s")) {
                object(entity, Set.of("id", "name"), Set.of("attributes", "refs"));
                values.add(compileEntity(entity, kind));
            }
        }

        private static ObjectNode compileEntity(JsonNode input, String kind) {
            ObjectNode value = JSON.createObjectNode().put("id", Protocol.id(input, "id")).put("kind", kind).put("name", text(input, "name"));
            JsonNode attributes = input.has("attributes") ? input.get("attributes") : JSON.createObjectNode();
            if (!attributes.isObject()) throw bad("实体attributes须为对象"); data(attributes);
            value.set("attributes", attributes.deepCopy());
            ArrayNode refs = input.has("refs") ? array(input, "refs") : JSON.createArrayNode();
            validateReferences(refs); value.set("refs", refs.deepCopy()); return value;
        }

        private static void groups(JsonNode input, ObjectNode seed, ObjectNode result) {
            ArrayNode values = result.putArray("entities");
            for (JsonNode group : rows(input, "groups")) {
                object(group, Set.of("id", "name", "populationFactId"), Set.of("attributes", "refs"));
                String factId = text(group, "populationFactId");
                ObjectNode population = find(array(seed, "facts"), factId, "群体人数事实");
                if (!text(population, "mode").equals("state") || !population.get("value").isIntegralNumber()
                    || population.get("value").bigIntegerValue().signum() < 0)
                    throw bad("群体人数必须引用非负整数state事实");
                ObjectNode value = compileEntity(group, "group");
                ObjectNode attributes = (ObjectNode) value.get("attributes");
                if (attributes.has("populationFactId") && !attributes.path("populationFactId").asText().equals(factId))
                    throw bad("群体人数事实引用冲突");
                attributes.put("populationFactId", factId); values.add(value);
            }
        }

        private static void extras(JsonNode input, ObjectNode seed, ObjectNode result) {
            ArrayNode characters = result.putArray("characters"), additions = result.putArray("entityRefsAdds");
            Map<String, Set<String>> expanded = new LinkedHashMap<>();
            Set<String> people = new HashSet<>(); array(seed, "characters").forEach(person -> people.add(person.path("id").asText()));
            for (JsonNode extra : rows(input, "extras")) {
                object(extra, Set.of("id", "name", "place", "publicProfile", "privateProfile", "groupId"), Set.of("birthYear"));
                ObjectNode person = extra.deepCopy(); person.remove("groupId"); validatePerson(person);
                String groupId = Protocol.id(extra, "groupId");
                ObjectNode group = find(array(seed, "entities"), groupId, "龙套所属群体");
                if (!text(group, "kind").equals("group")) throw bad("龙套必须关联group实体");
                if (!people.add(text(person, "id"))) throw bad("龙套人物标识重复");
                ObjectNode publicProfile = (ObjectNode) person.get("publicProfile");
                if (publicProfile.has("groupId") && !publicProfile.path("groupId").asText().equals(groupId)) throw bad("龙套群体归属冲突");
                publicProfile.put("groupId", groupId); characters.add(person);
                expanded.computeIfAbsent(groupId, ignored -> new LinkedHashSet<>()).add(text(person, "id"));
            }
            for (Map.Entry<String, Set<String>> entry : expanded.entrySet()) {
                ObjectNode group = find(array(seed, "entities"), entry.getKey(), "群体");
                Set<String> members = new HashSet<>(entry.getValue());
                for (JsonNode ref : array(group, "refs")) if (people.contains(ref.asText())) members.add(ref.asText());
                String factId = text(group.get("attributes"), "populationFactId");
                java.math.BigInteger total = find(array(seed, "facts"), factId, "群体人数事实").get("value").bigIntegerValue();
                if (java.math.BigInteger.valueOf(members.size()).compareTo(total) > 0)
                    throw bad("展开人物不能超过群体已声明总数：" + entry.getKey());
                ObjectNode addition = additions.addObject().put("entityId", entry.getKey());
                ArrayNode refs = addition.putArray("refs"); entry.getValue().forEach(refs::add);
            }
        }
    }
}
