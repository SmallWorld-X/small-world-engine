package creativeworld;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/** 构造管线及已声明约束的黑盒核对，不评价故事、情感推导或自然语言理解。 */
public final class GenesisCheck {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<ObjectNode> RESULTS = new ArrayList<>();
    private static Path testRoot, examples;
    private static int fixtureNumber;

    public static void main(String[] args) throws Exception {
        testRoot = Files.createTempDirectory("creative-world-genesis-check-");
        examples = args.length > 1 ? Path.of(args[1]) : Path.of("examples");
        if (args.length < 2 && !Files.exists(examples.resolve("genesis-river-town.json")))
            examples = Path.of("creative-world-engine", "examples");

        run("八个构造器均产生实际资料，证据与待补缺口分开", () -> {
            ObjectNode blueprint = blueprint(), original = blueprint.deepCopy();
            JsonNode draft = new WorldGenesis().build(blueprint), seed = draft.path("seed");
            equal(blueprint, original, "构造不得改调用者蓝图");
            equal(draft.path("kind").asText(), "world-draft", "返回草案身份");
            equal(seed.path("places").size(), 3, "三个行动地点");
            equal(seed.path("characters").size(), 4, "三位核心人物加一位龙套");
            equal(seed.path("entities").size(), 4, "地域、资源、势力、群体分别成立");
            equal(seed.path("relationships").size(), 2, "已声明关系进入起点");
            equal(seed.path("laws").size(), 2, "两种法则进入起点");
            equal(byId(seed.path("entities"), "river-region").path("kind").asText(), "region", "地域身份");
            equal(byId(seed.path("entities"), "iron-stock").path("attributes").path("quantityFactId").asText(), "iron-quantity", "资源绑定数量事实");
            equal(byId(seed.path("entities"), "port-guild").path("kind").asText(), "faction", "势力独立存在");
            JsonNode group = byId(seed.path("entities"), "dock-workers");
            equal(group.path("attributes").path("populationFactId").asText(), "dock-population", "群体绑定总人数事实");
            check(contains(group.path("refs"), "porter"), "展开龙套登记为群体成员");
            equal(byId(seed.path("characters"), "porter").path("publicProfile").path("groupId").asText(), "dock-workers", "龙套归属与群体登记相同");
            JsonNode construction = draft.path("construction");
            equal(construction.path("modules").size(), 8, "八个模块执行记录");
            equal(construction.path("evidence").size(), 8, "八个明确输入证据记录");
            output(construction, "world", "places", 3);
            output(construction, "person", "characters", 3);
            output(construction, "faction", "entities", 1);
            output(construction, "group", "entities", 1);
            output(construction, "extra", "characters", 1);
            output(construction, "relationship", "relationships", 2);
            output(construction, "emotion", "privateProfileAdds", 2);
            output(construction, "law", "laws", 2);
            for (JsonNode evidence : construction.path("evidence"))
                equal(evidence.path("method").asText(), "structured-input", "来源不能冒充模型推导");
            equal(construction.path("unresolved"), original.path("unresolved"), "缺口保留为缺口");
            Fixture f = fresh((ObjectNode) seed);
            equal(f.store.inspect().path("counts").path("entities").asInt(), 4, "确认后目录不是空占位");
            equal(f.store.inspect().path("counts").path("laws").asInt(), 2, "确认后法则登记");
            equal(f.store.view("@author").path("relationships").get(0).path("basis").asText(),
                original.path("sections").path("relationships").get(0).path("basis").asText(), "正式存档保留初始关系依据");
        });

        run("关系与初始感受各归本人，未获知的事实不随背景泄露", () -> {
            JsonNode draft = new WorldGenesis().build(blueprint()); Fixture f = fresh((ObjectNode) draft.path("seed"));
            JsonNode apprentice = f.store.view("apprentice"), boatman = f.store.view("boatman"), reader = f.store.view("@reader");
            equal(apprentice.path("self").path("privateProfile").path("initialEmotions").size(), 1, "本人能看初始感受");
            check(!apprentice.path("self").path("privateProfile").path("initialEmotions").get(0).path("trigger").asText().isBlank(), "感受有作者提供的触发依据");
            check(!byId(boatman.path("nearby"), "apprentice").has("privateProfile"), "附近他人不含私有感受");
            check(!byId(apprentice.path("nearby"), "boatman").has("privateProfile"), "船工私事不直接传给学徒");
            check(!apprentice.has("entities") && !apprentice.has("laws") && !boatman.has("entities"), "目录不自动给人物全量资料");
            check(!reader.has("entities") && !reader.has("laws") && !reader.has("characters"), "目录与人物私事不自动给读者");
            check(!hasKnowledge(apprentice, "cargo-location"), "学徒尚未知道铁料所在位置");
            equal(knowledge(boatman, "cargo-location").path("value").asText(), "warehouse", "船工持有已声明认识");
            equal(apprentice.path("relationships").size(), 2, "学徒可看自己的关系");
            equal(boatman.path("relationships").size(), 0, "关系不擅自反向复制");
            check(reader.path("readerKnowledge").isEmpty(), "构造不等于已向读者揭示");
            check(byId(draft.path("construction").path("modules"), "relationship").path("notes").size() > 1, "关系依据保留于构造记录");
        });

        run("群体人数与展开人物相符，未知归属和重复身份被拒绝", () -> {
            ObjectNode tooSmall = blueprint(); initialFact(tooSmall, "dock-population").put("value", 0);
            rejects(() -> new WorldGenesis().build(tooSmall));
            ObjectNode exact = blueprint(); initialFact(exact, "dock-population").put("value", 1);
            JsonNode exactSeed = new WorldGenesis().build(exact).path("seed");
            equal(byId(exactSeed.path("facts"), "dock-population").path("value").asInt(), 1, "边界人数一人可展开一人");
            ObjectNode large = blueprint();
            java.math.BigInteger largeCount = new java.math.BigInteger("9223372036854775808");
            initialFact(large, "dock-population").put("value", largeCount);
            JsonNode largeSeed = new WorldGenesis().build(large).path("seed");
            equal(byId(largeSeed.path("facts"), "dock-population").path("value").bigIntegerValue(), largeCount,
                "构造入口与内核使用一致的非负整数人数口径");
            ObjectNode overflow = exact.deepCopy(); ObjectNode second = extra(overflow).deepCopy(); second.put("id", "porter-two");
            ((ArrayNode) sections(overflow).path("extras")).add(second);
            rejects(() -> new WorldGenesis().build(overflow));
            ObjectNode unknown = blueprint(); extra(unknown).put("groupId", "missing-group"); rejects(() -> new WorldGenesis().build(unknown));
            ObjectNode wrongKind = blueprint(); extra(wrongKind).put("groupId", "port-guild"); rejects(() -> new WorldGenesis().build(wrongKind));
            ObjectNode duplicate = blueprint(); extra(duplicate).put("id", "apprentice"); rejects(() -> new WorldGenesis().build(duplicate));
            ObjectNode conflict = blueprint(); ((ObjectNode) extra(conflict).path("publicProfile")).put("groupId", "port-guild");
            rejects(() -> new WorldGenesis().build(conflict));
            ObjectNode fractional = blueprint(); initialFact(fractional, "dock-population").put("value", 1.5);
            rejects(() -> new WorldGenesis().build(fractional));
        });

        run("声明法则与目录引用在初始化时生效，构造冲突不产生正式世界", () -> {
            ObjectNode negative = blueprint(); initialFact(negative, "iron-quantity").put("value", -1);
            rejects(() -> new WorldGenesis().build(negative));
            ObjectNode outside = blueprint(); initialFact(outside, "cargo-location").put("value", "unknown-place");
            rejects(() -> new WorldGenesis().build(outside));
            ObjectNode missing = blueprint(); ((ObjectNode) sections(missing).path("laws").get(0)).put("factId", "not-a-fact");
            rejects(() -> new WorldGenesis().build(missing));
            ObjectNode conflict = readExample("genesis-conflict.json"); Path formal = nextPath();
            rejects(() -> buildThenInitialize(new WorldGenesis(), conflict, formal));
            check(!Files.exists(formal), "构造失败后没有正式世界文件");
            ObjectNode badSeed = (ObjectNode) new WorldGenesis().build(blueprint()).path("seed").deepCopy();
            byIdObject(badSeed.path("facts"), "iron-quantity").put("value", 101);
            Path invalid = nextPath(); rejects(() -> new WorldStore(invalid).initialize(badSeed));
            check(!Files.exists(invalid), "不能跳过构造器直接初始化违例种子");
        });

        run("范围与枚举在后续事务和重载后持续约束，组合失败全部不落账", () -> {
            Fixture f = freshDraft();
            f.store.apply(proposal(f, "quantity-low-bound", change("iron-quantity", JSON.valueToTree(40), JSON.valueToTree(0))));
            f.store.apply(proposal(f, "quantity-high-bound", change("iron-quantity", JSON.valueToTree(0), JSON.valueToTree(100))));
            unchangedOnReject(f, proposal(f, "below-bound", change("iron-quantity", JSON.valueToTree(100), JSON.valueToTree(-1))));
            unchangedOnReject(f, proposal(f, "above-bound", change("iron-quantity", JSON.valueToTree(100), JSON.valueToTree(101))));
            unchangedOnReject(f, proposal(f, "wrong-type", change("iron-quantity", JSON.valueToTree(100), JSON.valueToTree("many"))));
            ObjectNode first = effect("belief.add"); first.put("personId", "apprentice"); first.put("text", "这条私有想法也不应留下");
            unchangedOnReject(f, proposal(f, "combined-illegal", first,
                change("cargo-location", JSON.valueToTree("warehouse"), JSON.valueToTree("moon"))));
            f.store.apply(proposal(f, "legal-location", change("cargo-location", JSON.valueToTree("warehouse"), JSON.valueToTree("forge"))));
            JsonNode author = f.store.view("@author").deepCopy(), history = f.store.history().deepCopy();
            Fixture restored = new Fixture(f.path, new WorldStore(f.path));
            equal(restored.store.view("@author"), author, "恢复后保留目录及当前事实");
            equal(restored.store.history(), history, "恢复不重写历史");
            unchangedOnReject(restored, proposal(restored, "reloaded-illegal", change("cargo-location", JSON.valueToTree("forge"), JSON.valueToTree("moon"))));
            equal(byId(JSON.readTree(f.path.toFile()).path("seed").path("facts"), "iron-quantity").path("value").asInt(), 40, "起点数量不被后续变化覆盖");
        });

        run("群体上限不能在行动后失效，新增人物不能绕过成员登记", () -> {
            Fixture f = freshDraft();
            f.store.apply(proposal(f, "population-exact", change("dock-population", JSON.valueToTree(12), JSON.valueToTree(1))));
            unchangedOnReject(f, proposal(f, "population-too-low", change("dock-population", JSON.valueToTree(1), JSON.valueToTree(0))));
            ObjectNode addition = effect("person.add"), person = person("unregistered", "未登记成员", "warehouse");
            ((ObjectNode) person.path("publicProfile")).put("groupId", "dock-workers"); addition.set("person", person);
            unchangedOnReject(f, proposal(f, "unregistered-member", addition));
            Fixture restored = new Fixture(f.path, new WorldStore(f.path));
            unchangedOnReject(restored, proposal(restored, "restore-population-too-low", change("dock-population", JSON.valueToTree(1), JSON.valueToTree(0))));
            equal(restored.store.inspect().path("counts").path("characters").asInt(), 4, "拒绝成员扩展后人物数量不变");
        });

        run("地域资源势力引用不能悬空，身份与法则定义不能互相冒用", () -> {
            ObjectNode dangling = blueprint(); entity(dangling, "port-guild", "factions").putArray("refs").add("missing-person");
            rejects(() -> new WorldGenesis().build(dangling));
            ObjectNode duplicatedRef = blueprint(); entity(duplicatedRef, "port-guild", "factions").putArray("refs").add("boatman").add("boatman");
            rejects(() -> new WorldGenesis().build(duplicatedRef));
            ObjectNode identity = blueprint(); entity(identity, "port-guild", "factions").put("id", "apprentice");
            rejects(() -> new WorldGenesis().build(identity));
            ObjectNode quantityRef = blueprint(); ((ObjectNode) entity(quantityRef, "iron-stock", "world").path("attributes")).put("quantityFactId", "missing-fact");
            rejects(() -> new WorldGenesis().build(quantityRef));
            ObjectNode privateCollision = blueprint(); ((ObjectNode) sections(privateCollision).path("people").get(0).path("privateProfile")).putArray("initialEmotions");
            rejects(() -> new WorldGenesis().build(privateCollision));
            ObjectNode emptyOneOf = blueprint(); ((ObjectNode) sections(emptyOneOf).path("laws").get(1)).putArray("values");
            rejects(() -> new WorldGenesis().build(emptyOneOf));
            ObjectNode range = blueprint(); ((ObjectNode) sections(range).path("laws").get(0)).put("min", 200);
            rejects(() -> new WorldGenesis().build(range));
        });

        run("构造器按依赖而非注册顺序执行，缺失与循环在执行前拒绝", () -> {
            WorldGenesis ordered = new WorldGenesis(false); List<String> calls = new ArrayList<>();
            ordered.register(module("finish", Set.of("base"), "finish", context -> {
                calls.add("finish"); check(context.seed().path("places").size() == 1, "依赖资料先进入后续快照");
                ObjectNode contribution = JSON.createObjectNode(); contribution.putArray("characters").add(person("resident", "住户", "one-place")); return contribution;
            }));
            ordered.register(module("base", Set.of(), "world", context -> {
                calls.add("base"); ObjectNode contribution = JSON.createObjectNode(); contribution.putArray("places").add(place("one-place", "一处地点")); return contribution;
            }));
            JsonNode draft = ordered.build(minimalBlueprint());
            equal(calls, List.of("base", "finish"), "依赖拓扑顺序");
            Fixture f = fresh((ObjectNode) draft.path("seed")); equal(f.store.inspect().path("counts").path("characters").asInt(), 1, "自定义贡献通过共同内核校验");
            AtomicInteger executed = new AtomicInteger();
            WorldGenesis missing = new WorldGenesis(false).register(module("dependent", Set.of("missing"), "world", context -> {
                executed.incrementAndGet(); return JSON.createObjectNode();
            }));
            rejects(() -> missing.build(minimalBlueprint())); equal(executed.get(), 0, "缺失依赖不得部分执行");
            WorldGenesis cycle = new WorldGenesis(false)
                .register(module("a", Set.of("b"), "world", context -> { executed.incrementAndGet(); return JSON.createObjectNode(); }))
                .register(module("b", Set.of("a"), "b", context -> { executed.incrementAndGet(); return JSON.createObjectNode(); }));
            rejects(() -> cycle.build(minimalBlueprint())); equal(executed.get(), 0, "循环依赖不得部分执行");
            rejects(() -> ordered.register(module("base", Set.of(), "unused", context -> JSON.createObjectNode())));
        });

        run("拦截器可以拒绝构造，拒绝后不能留下正式世界", () -> {
            ObjectNode blueprint = blueprint(), original = blueprint.deepCopy(); List<String> calls = new ArrayList<>();
            WorldGenesis genesis = new WorldGenesis().intercept(new CreationInterceptor() {
                public void before(String id, WorldConstructor.Context context) {
                    calls.add(id); if (id.equals("person")) throw new IllegalArgumentException("本次准备缺少人物确认，拒绝构造");
                }
            });
            Path formal = nextPath(); rejects(() -> buildThenInitialize(genesis, blueprint, formal));
            check(!Files.exists(formal), "拦截拒绝不形成正式世界");
            check(calls.contains("world") && calls.contains("person") && !calls.contains("emotion"), "后续模块不越过拒绝点");
            equal(blueprint, original, "失败不改调用者资料");
            JsonNode successful = new WorldGenesis().build(blueprint);
            equal(successful.path("seed").path("characters").size(), 4, "同份资料可重新在独立管线构造");
        });

        run("拦截器只能修改独立快照，不能删除他模块资料或已声明法则", () -> {
            ObjectNode input = blueprint(), original = input.deepCopy(); JsonNode baseline = new WorldGenesis().build(input).path("seed");
            AtomicInteger callbacks = new AtomicInteger();
            WorldGenesis guarded = new WorldGenesis().intercept(new CreationInterceptor() {
                public void before(String id, WorldConstructor.Context context) {
                    callbacks.incrementAndGet(); context.blueprint().remove("sections"); context.seed().remove("characters");
                    JsonNode section = context.section(); if (section instanceof ObjectNode object) object.removeAll();
                    else if (section instanceof ArrayNode array) array.removeAll();
                }
                public void after(String id, WorldConstructor.Context context, JsonNode contribution) {
                    callbacks.incrementAndGet(); ((ObjectNode) contribution).removeAll(); context.seed().remove("laws");
                }
            });
            JsonNode actual = guarded.build(input);
            equal(callbacks.get(), 16, "每个模块前后拦截器确实被调用");
            equal(actual.path("seed"), baseline, "修改回调快照不得改权威合并结果");
            equal(input, original, "回调不得修改调用者蓝图");
        });

        run("扩展构造器上下文隔离，贡献必须通过合并和正式内核校验", () -> {
            ObjectNode input = blueprint(), original = input.deepCopy(); JsonNode baseline = new WorldGenesis().build(input).path("seed");
            WorldGenesis observer = new WorldGenesis().register(module("observer", Set.of("emotion", "law"), "observer", context -> {
                context.seed().remove("characters"); context.blueprint().remove("sections");
                equal(context.seed().path("characters").size(), 4, "每次读取返回独立快照");
                ObjectNode contribution = JSON.createObjectNode(); contribution.putArray("notes").add("仅检查快照隔离，没有构造新事实"); return contribution;
            }));
            equal(observer.build(input).path("seed"), baseline, "模块未提交的快照改动不成为资料");
            equal(input, original, "扩展模块不改调用者输入");
            WorldGenesis duplicate = new WorldGenesis().register(module("duplicate", Set.of("world"), "duplicate", context -> {
                ObjectNode contribution = JSON.createObjectNode(); contribution.putArray("places").add(place("wharf", "另一个码头")); return contribution;
            }));
            rejects(() -> duplicate.build(input));
            WorldGenesis override = new WorldGenesis().register(module("override", Set.of("emotion"), "override", context -> {
                ObjectNode contribution = JSON.createObjectNode(); contribution.putArray("privateProfileAdds").addObject()
                    .put("personId", "apprentice").putObject("fields").put("concern", "强行覆盖原顾虑"); return contribution;
            }));
            rejects(() -> override.build(input));
            WorldGenesis unsupported = new WorldGenesis().register(module("unsupported", Set.of(), "unsupported", context -> {
                ObjectNode contribution = JSON.createObjectNode(); contribution.put("worldId", "injected-world"); return contribution;
            }));
            rejects(() -> unsupported.build(input));
            equal(new WorldGenesis().build(input).path("seed"), baseline, "失败构造不污染随后独立结果");
        });

        run("旧种子继续有效，法则定义严格且草案确认后与调用者树隔离", () -> {
            Fixture legacy = fresh(readExample("river-town.json"));
            equal(legacy.store.inspect().path("counts").path("entities").asInt(), 0, "旧种子可缺省目录");
            equal(legacy.store.inspect().path("counts").path("laws").asInt(), 0, "旧种子可缺省法则");
            legacy.store.apply(proposal(legacy, "legacy-forward", change("cargo-location", JSON.valueToTree("warehouse"), JSON.valueToTree("forge"))));
            equal(new WorldStore(legacy.path).inspect().path("revision").asInt(), 1, "旧世界仍可向前追加");
            ObjectNode noBound = blueprint(); ObjectNode law = (ObjectNode) sections(noBound).path("laws").get(0); law.remove("min"); law.remove("max");
            rejects(() -> new WorldGenesis().build(noBound));
            ObjectNode numberDuplicate = blueprint(); ObjectNode choice = (ObjectNode) sections(numberDuplicate).path("laws").get(1);
            choice.put("factId", "iron-quantity"); choice.putArray("values").add(40).add(40.0);
            rejects(() -> new WorldGenesis().build(numberDuplicate));
            ObjectNode draft = new WorldGenesis().build(blueprint()); Fixture f = fresh((ObjectNode) draft.path("seed"));
            JsonNode confirmed = f.store.view("@author").deepCopy(); ((ObjectNode) draft.path("seed")).remove("characters");
            equal(f.store.view("@author"), confirmed, "确认保存与之后被修改的调用者树隔离");
        });

        ObjectNode report = JSON.createObjectNode(); long passed = RESULTS.stream().filter(row -> row.path("passed").asBoolean()).count();
        report.put("checks", RESULTS.size()); report.put("passed", passed); report.put("failed", RESULTS.size() - passed);
        report.set("results", JSON.valueToTree(RESULTS)); report.put("actualModelCalled", false); report.put("formalServiceUsed", false);
        report.put("proseQualityEvaluated", false); report.put("cliDraftTamperEvaluated", false);
        report.put("evidenceLimit", "仅核对结构化构造、快照隔离和显式目录约束；不证明自然语言理解、人物心理或小说质量，也不是恶意Java代码安全沙盒。CLI草案篡改另行核对。");
        report.put("isolatedTestDirectory", testRoot.toString()); report.put("examplesDirectory", examples.toAbsolutePath().toString());
        if (args.length > 0) {
            Path output = Path.of(args[0]).toAbsolutePath(); Files.createDirectories(output.getParent());
            JSON.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);
        }
        System.out.println(JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report));
        if (passed != RESULTS.size()) System.exit(1);
    }

    private record Fixture(Path path, WorldStore store) {}
    private static Path nextPath() { return testRoot.resolve("world-" + ++fixtureNumber + ".json"); }
    private static Fixture fresh(ObjectNode seed) throws Exception { Path path = nextPath(); WorldStore store = new WorldStore(path); store.initialize(seed); return new Fixture(path, store); }
    private static Fixture freshDraft() throws Exception { return fresh((ObjectNode) new WorldGenesis().build(blueprint()).path("seed")); }
    private static void buildThenInitialize(WorldGenesis genesis, JsonNode input, Path path) throws Exception { new WorldStore(path).initialize(genesis.build(input).path("seed")); }
    private static ObjectNode readExample(String name) throws Exception { return (ObjectNode) JSON.readTree(examples.resolve(name).toFile()); }
    private static ObjectNode blueprint() throws Exception { return readExample("genesis-river-town.json"); }
    private static ObjectNode sections(ObjectNode blueprint) { return (ObjectNode) blueprint.path("sections"); }
    private static ObjectNode initialFact(ObjectNode blueprint, String id) { return byIdObject(sections(blueprint).path("world").path("facts"), id); }
    private static ObjectNode extra(ObjectNode blueprint) { return (ObjectNode) sections(blueprint).path("extras").get(0); }
    private static ObjectNode entity(ObjectNode blueprint, String id, String section) {
        JsonNode values = sections(blueprint).path(section); if (section.equals("world")) values = values.path("entities"); return byIdObject(values, id);
    }
    private static ObjectNode minimalBlueprint() {
        ObjectNode value = JSON.createObjectNode().put("schemaVersion", 1).put("worldId", "custom-world").put("title", "扩展构造试验").put("idea", "只核对依赖与贡献，不生成故事");
        value.putObject("sections").putObject("world"); return value;
    }
    private static WorldConstructor module(String id, Set<String> dependencies, String section, Construct action) {
        return new WorldConstructor() {
            public String id() { return id; }
            public Set<String> dependencies() { return dependencies; }
            public String sectionName() { return section; }
            public ObjectNode construct(Context context) { return action.apply(context); }
        };
    }
    private static ObjectNode place(String id, String name) { return JSON.createObjectNode().put("id", id).put("name", name); }
    private static ObjectNode person(String id, String name, String place) {
        ObjectNode value = JSON.createObjectNode().put("id", id).put("name", name).put("place", place); value.putObject("publicProfile"); value.putObject("privateProfile"); return value;
    }
    private static ObjectNode effect(String type) { return JSON.createObjectNode().put("type", type); }
    private static ObjectNode change(String id, JsonNode expected, JsonNode value) {
        ObjectNode effect = effect("fact.change").put("factId", id); effect.set("expectedValue", expected); effect.set("value", value); return effect;
    }
    private static ObjectNode proposal(Fixture f, String requestId, ObjectNode... effects) throws Exception {
        ObjectNode value = JSON.createObjectNode().put("schemaVersion", 1).put("worldId", f.store.inspect().path("worldId").asText())
            .put("requestId", requestId).put("expectedRevision", f.store.inspect().path("revision").asInt()).put("actor", "@author").put("reason", "验证已声明约束而非推断叙事结果");
        ArrayNode list = value.putArray("effects"); for (ObjectNode effect : effects) list.add(effect); return value;
    }
    private static JsonNode byId(JsonNode array, String id) { for (JsonNode value : array) if (value.path("id").asText().equals(id)) return value; throw new AssertionError("缺少资料：" + id); }
    private static ObjectNode byIdObject(JsonNode array, String id) { return (ObjectNode) byId(array, id); }
    private static boolean contains(JsonNode array, String text) { for (JsonNode value : array) if (value.asText().equals(text)) return true; return false; }
    private static JsonNode knowledge(JsonNode view, String factId) { for (JsonNode value : view.path("knowledge")) if (value.path("factId").asText().equals(factId)) return value; throw new AssertionError("缺少认识：" + factId); }
    private static boolean hasKnowledge(JsonNode view, String factId) { for (JsonNode value : view.path("knowledge")) if (value.path("factId").asText().equals(factId)) return true; return false; }
    private static void output(JsonNode construction, String module, String type, int count) { equal(byId(construction.path("modules"), module).path("outputs").path(type).asInt(), count, "模块实际贡献：" + module + "/" + type); }
    private static void unchangedOnReject(Fixture f, JsonNode proposal) throws Exception {
        byte[] bytes = Files.readAllBytes(f.path); JsonNode author = f.store.view("@author").deepCopy(), history = f.store.history().deepCopy();
        rejects(() -> f.store.apply(proposal));
        check(Arrays.equals(bytes, Files.readAllBytes(f.path)), "拒绝后正式文件字节变化");
        equal(f.store.view("@author"), author, "拒绝后世界资料变化"); equal(f.store.history(), history, "拒绝后历史变化");
    }
    private static void rejects(Checked action) throws Exception {
        try { action.run(); } catch (IllegalArgumentException | IllegalStateException | java.io.IOException expected) { return; }
        throw new AssertionError("应当拒绝的操作被接受");
    }
    private static void run(String name, Checked action) {
        ObjectNode result = JSON.createObjectNode().put("name", name);
        try { action.run(); result.put("passed", true); }
        catch (Exception | AssertionError failure) { result.put("passed", false).put("error", failure.getClass().getSimpleName() + ": " + failure.getMessage()); }
        RESULTS.add(result);
    }
    private static void equal(Object actual, Object expected, String reason) { check(java.util.Objects.equals(actual, expected), reason + "；预期=" + expected + "；实际=" + actual); }
    private static void check(boolean condition, String reason) { if (!condition) throw new AssertionError(reason); }
    @FunctionalInterface private interface Checked { void run() throws Exception; }
    @FunctionalInterface private interface Construct { ObjectNode apply(WorldConstructor.Context context); }
}
