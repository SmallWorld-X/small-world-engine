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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** 构造版本选择和草案重新核对的独立检查，不评价小说或模型能力。 */
public final class ConstructorVersionCheck {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<ObjectNode> RESULTS = new ArrayList<>();
    private static final List<String> IDENTITY_FIELDS = List.of("id", "version", "section", "dependencies");
    private static Path testRoot, examples;
    private static int fixtureNumber;

    public static void main(String[] args) throws Exception {
        testRoot = Files.createTempDirectory("creative-world-constructor-version-check-");
        examples = args.length > 1 ? Path.of(args[1]) : Path.of("examples");
        if (args.length < 2 && !Files.exists(examples.resolve("genesis-river-town.json")))
            examples = Path.of("creative-world-engine", "examples");

        run("默认版本为1，旧八模块草案仍可完整重新确认", () -> {
            WorldConstructor legacy = legacyBase(new AtomicInteger());
            equal(legacy.version(), "1", "未实现version的旧扩展固定为1");
            ObjectNode input = (ObjectNode) JSON.readTree(examples.resolve("genesis-river-town.json").toFile());
            ObjectNode original = input.deepCopy(), draft = new WorldGenesis().build(input);
            equal(draft.path("construction").path("modules").size(), 8, "旧内置模块数量");
            for (JsonNode row : draft.path("construction").path("modules")) {
                check(!row.has("version") && !row.has("section") && !row.has("dependencies"), "旧模块记录不补新字段");
            }
            for (JsonNode row : draft.path("construction").path("evidence"))
                check(!row.has("moduleVersion"), "旧证据记录不补版号");
            equal(new WorldGenesis().confirmSeed(JSON.readTree(JSON.writeValueAsBytes(draft))), draft.path("seed"), "旧草案完整比对");
            equal(input, original, "构造和确认不改调用者蓝图");
        });

        run("同标识两版本产生不同事实，旧蓝图始终选1而不是最新版本", () -> {
            AtomicInteger baseCalls = new AtomicInteger(), oneCalls = new AtomicInteger(), twoCalls = new AtomicInteger();
            WorldGenesis genesis = new WorldGenesis(false).register(cargo("2", "forge", twoCalls))
                .register(legacyBase(baseCalls)).register(cargo("1", "warehouse", oneCalls));
            ObjectNode old = genesis.build(blueprint());
            equal(fact(old, "cargo-location").path("value").asText(), "warehouse", "旧蓝图实际使用版本1产物");
            equal(oneCalls.get(), 1, "版本1实际执行"); equal(twoCalls.get(), 0, "版本2不被默认执行");
            ObjectNode newer = genesis.build(selected("cargo", "2", "base", "1"));
            equal(fact(newer, "cargo-location").path("value").asText(), "forge", "版本2实际产生不同位置");
            equal(baseCalls.get(), 2, "各次依赖只执行一次"); equal(oneCalls.get(), 1, "显式2未调用1");
            equal(twoCalls.get(), 1, "版本2实际执行");
            equal(genesis.confirmSeed(old), old.path("seed"), "宿主同时有2仍可确认旧1草案");
            equal(twoCalls.get(), 1, "确认旧草案不能偷用2");
        });

        run("显式选择只按选定集合排序，记录版本和依赖且忽略未选实现", () -> {
            AtomicInteger unused = new AtomicInteger();
            WorldGenesis genesis = standard(new AtomicInteger(), new AtomicInteger())
                .register(module("unused", "9", "world", Set.of("missing"), context -> {
                    unused.incrementAndGet(); throw new IllegalArgumentException("未选实现不得运行");
                }));
            ObjectNode draft = genesis.build(selected("cargo", "2", "base", "1"));
            equal(draft.path("construction").path("moduleOrder"), JSON.valueToTree(List.of("base", "cargo")), "选择顺序服从依赖");
            JsonNode row = byId(draft.path("construction").path("modules"), "cargo");
            equal(row.path("version").asText(), "2", "实际构造版本");
            equal(row.path("section").asText(), "cargo", "实际资料名称");
            equal(row.path("dependencies"), JSON.valueToTree(List.of("base")), "实际依赖");
            JsonNode source = evidence(draft, "cargo");
            equal(source.path("moduleVersion").asText(), "2", "贡献证据版本");
            equal(source.path("outputs").path("facts").asInt(), 1, "确有事实贡献");
            equal(unused.get(), 0, "未选实现的缺依赖、同section与回调都不干扰");
            equal(genesis.confirmSeed(draft), draft.path("seed"), "显式草案可以重新确认");
            equal(unused.get(), 0, "确认仍只执行选定集合");
        });

        run("只注册2不能接管旧1或不存在的精准版本，拒绝前无回调", () -> {
            AtomicInteger base = new AtomicInteger(), two = new AtomicInteger();
            WorldGenesis onlyTwo = new WorldGenesis(false).register(legacyBase(base)).register(cargo("2", "forge", two));
            rejects(() -> onlyTwo.build(blueprint()));
            rejects(() -> onlyTwo.build(selected("base", "1", "cargo", "1")));
            rejects(() -> onlyTwo.build(selected("base", "1", "cargo", "3")));
            ObjectNode old = standard(new AtomicInteger(), new AtomicInteger()).build(blueprint());
            rejects(() -> onlyTwo.confirmSeed(old));
            equal(base.get() + two.get(), 0, "缺少精准版本时依赖也不得部分执行");
        });

        run("版号类型与空白换行严格拒绝，注册失败不会执行回调", () -> {
            for (JsonNode invalid : List.of(JSON.nullNode(), JSON.valueToTree(2), JSON.valueToTree(""),
                JSON.valueToTree(" \t"), JSON.valueToTree("1\n2"), JSON.valueToTree("1\r2"))) {
                AtomicInteger calls = new AtomicInteger(); WorldGenesis genesis = counted(calls);
                ObjectNode input = selected("base", "1", "cargo", "1");
                ((ObjectNode) input.path("constructors").get(1)).set("version", invalid.deepCopy());
                rejects(() -> genesis.build(input)); equal(calls.get(), 0, "无效蓝图版号不能执行");
            }
            for (String invalid : new String[]{null, "", " ", "1\n2", "1\r2"}) {
                AtomicInteger calls = new AtomicInteger();
                rejects(() -> new WorldGenesis(false).register(module("bad", invalid, "world", Set.of(), context -> {
                    calls.incrementAndGet(); return baseContribution();
                })));
                equal(calls.get(), 0, "无效注册版号不能执行");
            }
        });

        run("重复元组与一次选择同标识多版本均拒绝，选择记录不接受额外字段", () -> {
            AtomicInteger calls = new AtomicInteger(); WorldGenesis genesis = counted(calls);
            rejects(() -> genesis.register(cargo("1", "forge", calls)));
            for (ObjectNode invalid : List.of(selected("base", "1", "cargo", "1", "cargo", "2"),
                selected("base", "1", "cargo", "1", "cargo", "1"))) rejects(() -> genesis.build(invalid));
            ObjectNode extra = selected("base", "1", "cargo", "1");
            ((ObjectNode) extra.path("constructors").get(1)).put("fallback", "2"); rejects(() -> genesis.build(extra));
            ObjectNode missing = selected("base", "1", "cargo", "1");
            ((ObjectNode) missing.path("constructors").get(1)).remove("version"); rejects(() -> genesis.build(missing));
            ObjectNode empty = blueprint(); empty.putArray("constructors"); rejects(() -> genesis.build(empty));
            equal(calls.get(), 0, "选择协议错误均在所有回调前拒绝");
        });

        run("未选依赖、缺依赖、循环和选中资料冲突均在回调前拒绝", () -> {
            AtomicInteger calls = new AtomicInteger(); WorldGenesis genesis = counted(calls);
            rejects(() -> genesis.build(selected("cargo", "2")));
            WorldGenesis missing = new WorldGenesis(false).register(module("base", "1", "world", Set.of("absent"), context -> {
                calls.incrementAndGet(); return baseContribution();
            }));
            rejects(() -> missing.build(selected("base", "1")));
            WorldGenesis cycle = new WorldGenesis(false)
                .register(module("base", "1", "world", Set.of("cargo"), context -> { calls.incrementAndGet(); return baseContribution(); }))
                .register(module("cargo", "2", "cargo", Set.of("base"), context -> { calls.incrementAndGet(); return cargoContribution("forge"); }));
            rejects(() -> cycle.build(selected("base", "1", "cargo", "2")));
            WorldGenesis collision = new WorldGenesis(false).register(legacyBase(calls))
                .register(module("cargo", "2", "world", Set.of("base"), context -> { calls.incrementAndGet(); return cargoContribution("forge"); }));
            rejects(() -> collision.build(selected("base", "1", "cargo", "2")));
            equal(calls.get(), 0, "无效图不得构造部分资料");
        });

        run("注册后改变构造器标识版本资料名或依赖均拒绝", () -> {
            for (String field : IDENTITY_FIELDS) {
                MutableConstructor moving = new MutableConstructor(null);
                AtomicInteger cargoCalls = new AtomicInteger();
                WorldGenesis genesis = new WorldGenesis(false).register(moving).register(cargo("1", "warehouse", cargoCalls));
                moving.change(field);
                rejects(() -> genesis.build(selected("base", "1", "cargo", "1")));
                equal(moving.calls.get() + cargoCalls.get(), 0, "注册后漂移不能进入构造：" + field);
            }
        });

        run("构造回调内身份漂移不能形成草案或执行后续构造器", () -> {
            for (String field : IDENTITY_FIELDS) {
                MutableConstructor moving = new MutableConstructor(field);
                AtomicInteger cargoCalls = new AtomicInteger();
                WorldGenesis genesis = new WorldGenesis(false).register(moving).register(cargo("1", "warehouse", cargoCalls));
                Path formal = nextPath("callback-drift-world");
                rejects(() -> new WorldStore(formal).initialize(genesis.confirmSeed(genesis.build(selected("base", "1", "cargo", "1")))));
                equal(moving.calls.get(), 1, "本次回调确已执行");
                equal(cargoCalls.get(), 0, "身份漂移后依赖者不得执行：" + field);
                check(!Files.exists(formal), "漂移失败不能落正式世界");
            }
        });

        run("前后拦截器改变任何已选构造器身份都立即拒绝", () -> {
            for (boolean after : List.of(false, true)) for (String field : IDENTITY_FIELDS) {
                MutableConstructor moving = new MutableConstructor(null); AtomicInteger cargoCalls = new AtomicInteger();
                WorldGenesis genesis = new WorldGenesis(false).register(moving).register(cargo("1", "warehouse", cargoCalls));
                genesis.intercept(new CreationInterceptor() {
                    public void before(String id, WorldConstructor.Context context) { if (!after && id.equals("base")) moving.change(field); }
                    public void after(String id, WorldConstructor.Context context, JsonNode contribution) { if (after && id.equals("base")) moving.change(field); }
                });
                Path formal = nextPath("interceptor-drift-world");
                rejects(() -> new WorldStore(formal).initialize(genesis.confirmSeed(genesis.build(selected("base", "1", "cargo", "1")))));
                equal(moving.calls.get(), after ? 1 : 0, "前后身份检查位置：" + field);
                equal(cargoCalls.get(), 0, "拦截漂移后不执行后续模块"); check(!Files.exists(formal), "无正式世界");
            }
            MutableConstructor later = new MutableConstructor(null); AtomicInteger baseCalls = new AtomicInteger();
            // 为后一个模块选择不同资料名，确保先验证身份漂移而不是资料冲突。
            later.section = "later";
            WorldGenesis captured = new WorldGenesis(false)
                .register(module("first", "1", "world", Set.of(), context -> { baseCalls.incrementAndGet(); return baseContribution(); }))
                .register(later).intercept(new CreationInterceptor() {
                    public void before(String id, WorldConstructor.Context context) { if (id.equals("first")) later.change("version"); }
                });
            ObjectNode input = selected("first", "1", "base", "1");
            ((ObjectNode) input.path("sections")).remove("cargo");
            rejects(() -> captured.build(input));
            equal(baseCalls.get(), 0, "检查整个选定集合而非仅当前模块"); equal(later.calls.get(), 0, "后续漂移模块未执行");
        });

        run("草案事实证据版本被篡改均拒绝，原草案字节不变且无正式世界", () -> {
            WorldGenesis genesis = standard(new AtomicInteger(), new AtomicInteger());
            ObjectNode draft = genesis.build(selected("base", "1", "cargo", "2"));
            Path source = nextPath("original-draft"); JSON.writeValue(source.toFile(), draft);
            byte[] original = Files.readAllBytes(source);
            List<ObjectNode> altered = new ArrayList<>();
            ObjectNode value = draft.deepCopy(); ((ObjectNode) fact(value, "cargo-location")).put("value", "wharf"); altered.add(value);
            value = draft.deepCopy(); ((ObjectNode) byId(value.path("construction").path("modules"), "cargo")).put("version", "1"); altered.add(value);
            value = draft.deepCopy(); ((ObjectNode) byId(value.path("construction").path("modules"), "cargo")).putArray("dependencies"); altered.add(value);
            value = draft.deepCopy(); ((ObjectNode) evidence(value, "cargo")).put("moduleVersion", "1"); altered.add(value);
            value = draft.deepCopy(); ((ArrayNode) value.path("construction").path("notes")).add("未经重新构造的追加说明"); altered.add(value);
            value = draft.deepCopy(); ((ObjectNode) value.path("blueprint").path("constructors").get(1)).put("version", "1"); altered.add(value);
            value = draft.deepCopy(); value.put("schemaVersion", 2); altered.add(value);
            value = draft.deepCopy(); value.put("kind", "world-store"); altered.add(value);
            value = draft.deepCopy(); value.put("confirmed", true); altered.add(value);
            value = draft.deepCopy(); value.remove("construction"); altered.add(value);
            for (ObjectNode changed : altered) {
                Path formal = nextPath("rejected-confirm-world");
                rejects(() -> new WorldStore(formal).initialize(genesis.confirmSeed(changed)));
                check(!Files.exists(formal), "篡改确认不得落正式世界");
                check(Arrays.equals(original, Files.readAllBytes(source)), "确认拒绝不能重写原草案");
            }
            equal(genesis.confirmSeed(JSON.readTree(source.toFile())), draft.path("seed"), "原草案仍可确认");
        });

        run("合法确认只返回种子，初始化无事件，随后询问和沿路移动仍工作", () -> {
            WorldGenesis genesis = standard(new AtomicInteger(), new AtomicInteger());
            ObjectNode draft = genesis.build(selected("cargo", "2", "base", "1")), original = draft.deepCopy();
            Path formal = nextPath("confirmed-world"); ObjectNode seed = genesis.confirmSeed(draft);
            check(!Files.exists(formal), "confirmSeed自身不写正式世界");
            WorldStore world = new WorldStore(formal); world.initialize(seed);
            equal(world.inspect().path("revision").asInt(), 0, "构造不成为行动版本");
            equal(world.history().path("events").size(), 0, "初始化没有虚构经历");
            check(!hasKnowledge(world.view("apprentice"), "cargo-location"), "人物尚未知他人消息");
            ObjectNode ask = action(world, "ask-after-confirm").put("target", "boatman").put("factId", "cargo-location");
            world.step("ask", ask);
            JsonNode known = knowledge(world.view("apprentice"), "cargo-location");
            equal(known.path("value").asText(), "forge", "行动使用已选2真实构造产物");
            equal(known.path("source").asText(), "boatman", "认识有同地人物来源");
            world.step("travel", action(world, "travel-after-confirm").put("to", "warehouse"));
            equal(new WorldStore(formal).view("apprentice").path("self").path("place").asText(), "warehouse", "保存后移动可重放");
            equal(world.history().path("events").size(), 2, "仅两次合法行动形成事件");
            equal(draft, original, "确认与后续行动不改原草案");
        });

        run("同版实现产物改变会拒绝旧草案，版本编号不冒充代码签名", () -> {
            ObjectNode old = new WorldGenesis(false).register(legacyBase(new AtomicInteger()))
                .register(cargo("1", "warehouse", new AtomicInteger())).build(blueprint());
            AtomicInteger changedCalls = new AtomicInteger();
            WorldGenesis changed = new WorldGenesis(false).register(legacyBase(new AtomicInteger()))
                .register(cargo("1", "forge", changedCalls));
            Path formal = nextPath("changed-source-world");
            rejects(() -> new WorldStore(formal).initialize(changed.confirmSeed(old)));
            equal(changedCalls.get(), 1, "确实重新执行同版当前实现并发现产物差异");
            check(!Files.exists(formal), "同版产物不符不能落世界");
            equal(fact(old, "cargo-location").path("value").asText(), "warehouse", "拒绝不改旧草案");
        });

        run("模块和拦截器收到独立快照，不能偷偷改变蓝图或他模块贡献", () -> {
            ObjectNode input = selected("base", "1", "cargo", "2"), original = input.deepCopy();
            ObjectNode baseline = standard(new AtomicInteger(), new AtomicInteger()).build(input);
            ObjectNode returnedBase = baseContribution();
            WorldGenesis genesis = new WorldGenesis(false)
                .register(module("base", "1", "world", Set.of(), context -> {
                    context.blueprint().remove("constructors"); context.seed().put("title", "私自更名");
                    ((ObjectNode) context.section()).put("label", "修改快照");
                    equal(context.blueprint().path("constructors").size(), 2, "蓝图getter不共享");
                    equal(context.seed().path("title").asText(), input.path("title").asText(), "种子getter不共享");
                    equal(context.section().path("label").asText(), "原始地点资料", "资料getter不共享");
                    return returnedBase;
                }))
                .register(module("cargo", "2", "cargo", Set.of("base"), context -> {
                    equal(context.seed().path("characters").size(), 2, "后续仍看到已确认贡献");
                    ((ArrayNode) context.seed().path("characters")).removeAll();
                    equal(context.seed().path("characters").size(), 2, "后续不能改已合并资料");
                    return cargoContribution("forge");
                }))
                .intercept(new CreationInterceptor() {
                    public void before(String id, WorldConstructor.Context context) {
                        context.blueprint().removeAll(); context.seed().removeAll();
                    }
                    public void after(String id, WorldConstructor.Context context, JsonNode contribution) {
                        ((ObjectNode) contribution).removeAll(); context.seed().removeAll();
                        if (id.equals("base")) returnedBase.removeAll();
                    }
                });
            ObjectNode draft = genesis.build(input);
            equal(draft, baseline, "修改快照和已返回原树不能改变权威构造");
            equal(input, original, "调用者蓝图保持不变");
        });

        run("确认返回值深拷贝，调用者修改返回种子不能污染草案或下次确认", () -> {
            WorldGenesis genesis = standard(new AtomicInteger(), new AtomicInteger());
            ObjectNode draft = genesis.build(selected("base", "1", "cargo", "1")), original = draft.deepCopy();
            ObjectNode confirmed = genesis.confirmSeed(draft);
            ((ObjectNode) byId(confirmed.path("characters"), "apprentice")).put("name", "调用者临时改名");
            ((ArrayNode) confirmed.path("facts")).removeAll();
            equal(draft, original, "确认产物与输入草案分离");
            equal(genesis.confirmSeed(draft), original.path("seed"), "随后确认不受上次返回值变更污染");
            ObjectNode reordered = JSON.createObjectNode();
            List<String> names = new ArrayList<>(); draft.fieldNames().forEachRemaining(names::add);
            java.util.Collections.reverse(names); for (String name : names) reordered.set(name, draft.get(name).deepCopy());
            reordered.put("schemaVersion", 1L);
            equal(genesis.confirmSeed(reordered), original.path("seed"), "字段顺序和等值整数类型不伪造差异");
        });

        run("构造等待期间调用者修改输入不会改本次选定版本和原始资料", () -> {
            ObjectNode input = selected("base", "1", "cargo", "1"), frozen = input.deepCopy();
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            AtomicInteger twoCalls = new AtomicInteger();
            WorldGenesis genesis = new WorldGenesis(false)
                .register(module("base", "1", "world", Set.of(), context -> {
                    entered.countDown(); await(release);
                    equal(context.section().path("label").asText(), "原始地点资料", "等待前已冻结资料");
                    return baseContribution();
                })).register(cargo("1", "warehouse", new AtomicInteger())).register(cargo("2", "forge", twoCalls));
            ExecutorService pool = Executors.newSingleThreadExecutor();
            try {
                Future<ObjectNode> pending = pool.submit(() -> genesis.build(input));
                await(entered);
                ((ObjectNode) input.path("constructors").get(1)).put("version", "2");
                ((ObjectNode) input.path("sections").path("world")).put("label", "等待期间已改变");
                input.put("title", "等待期间改标题"); release.countDown();
                ObjectNode draft = pending.get(5, TimeUnit.SECONDS);
                equal(draft.path("blueprint"), frozen, "本次绑定完整输入快照");
                equal(fact(draft, "cargo-location").path("value").asText(), "warehouse", "等待变更不能偷换1到2");
                equal(twoCalls.get(), 0, "版本2未被执行");
            } finally {
                release.countDown(); pool.shutdownNow(); check(pool.awaitTermination(5, TimeUnit.SECONDS), "测试线程须有界退出");
            }
        });

        ObjectNode report = JSON.createObjectNode(); long passed = RESULTS.stream().filter(row -> row.path("passed").asBoolean()).count();
        report.put("checks", RESULTS.size()).put("passed", passed).put("failed", RESULTS.size() - passed);
        report.set("results", JSON.valueToTree(RESULTS)); report.put("actualModelCalled", false).put("formalServiceUsed", false);
        report.put("proseQualityEvaluated", false).put("cliEvaluated", false);
        report.put("evidenceLimit", "仅核对本地构造版本选择、依赖、快照、草案完整重建比对与确认后行动；不证明小说质量、模型能力、源码签名或任意Java扩展的安全隔离。");
        report.put("isolatedTestDirectory", testRoot.toString()).put("examplesDirectory", examples.toAbsolutePath().toString());
        if (args.length > 0) {
            Path output = Path.of(args[0]).toAbsolutePath(); Files.createDirectories(output.getParent());
            JSON.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);
        }
        System.out.println(JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report));
        if (passed != RESULTS.size()) System.exit(1);
    }

    private static WorldGenesis standard(AtomicInteger one, AtomicInteger two) {
        return new WorldGenesis(false).register(legacyBase(new AtomicInteger()))
            .register(cargo("1", "warehouse", one)).register(cargo("2", "forge", two));
    }
    private static WorldGenesis counted(AtomicInteger calls) {
        return new WorldGenesis(false).register(legacyBase(calls))
            .register(cargo("1", "warehouse", calls)).register(cargo("2", "forge", calls));
    }
    private static WorldConstructor legacyBase(AtomicInteger calls) {
        return new WorldConstructor() {
            public String id() { return "base"; }
            public String sectionName() { return "world"; }
            public Set<String> dependencies() { return Set.of(); }
            public ObjectNode construct(Context context) { calls.incrementAndGet(); return baseContribution(); }
        };
    }
    private static WorldConstructor cargo(String version, String location, AtomicInteger calls) {
        return module("cargo", version, "cargo", Set.of("base"), context -> {
            calls.incrementAndGet();
            equal(context.seed().path("characters").size(), 2, "事实构造读取依赖贡献");
            return cargoContribution(location);
        });
    }
    private static WorldConstructor module(String id, String version, String section, Set<String> dependencies, Construct action) {
        return new WorldConstructor() {
            public String id() { return id; }
            public String version() { return version; }
            public String sectionName() { return section; }
            public Set<String> dependencies() { return dependencies; }
            public ObjectNode construct(Context context) { return action.apply(context); }
        };
    }
    private static final class MutableConstructor implements WorldConstructor {
        private String id = "base", version = "1", section = "world";
        private Set<String> dependencies = Set.of();
        private final AtomicInteger calls = new AtomicInteger();
        private final String during;
        private MutableConstructor(String during) { this.during = during; }
        public String id() { return id; }
        public String version() { return version; }
        public String sectionName() { return section; }
        public Set<String> dependencies() { return dependencies; }
        public ObjectNode construct(Context context) { calls.incrementAndGet(); if (during != null) change(during); return baseContribution(); }
        private void change(String field) {
            switch (field) {
                case "id" -> id = "changed-base";
                case "version" -> version = "2";
                case "section" -> section = "changed-world";
                case "dependencies" -> dependencies = Set.of("cargo");
                default -> throw new AssertionError("未知身份字段：" + field);
            }
        }
    }
    private static ObjectNode blueprint() {
        ObjectNode result = JSON.createObjectNode().put("schemaVersion", 1).put("worldId", "constructor-version-town")
            .put("title", "构造版本核对小世界").put("idea", "核对显式资料处理与版本选择，不生成小说");
        ObjectNode sections = result.putObject("sections");
        sections.putObject("world").put("label", "原始地点资料"); sections.putObject("cargo").put("label", "货物资料");
        return result;
    }
    private static ObjectNode selected(String... pairs) {
        check(pairs.length % 2 == 0, "选择参数必须成对"); ObjectNode result = blueprint(); ArrayNode selected = result.putArray("constructors");
        for (int i = 0; i < pairs.length; i += 2) selected.addObject().put("id", pairs[i]).put("version", pairs[i + 1]);
        return result;
    }
    private static ObjectNode baseContribution() {
        ObjectNode result = JSON.createObjectNode();
        result.putArray("places").add(place("wharf", "河埠")).add(place("warehouse", "货栈")).add(place("forge", "铁坊"));
        result.putArray("routes").addObject().put("from", "wharf").put("to", "warehouse");
        ((ArrayNode) result.path("routes")).addObject().put("from", "warehouse").put("to", "forge");
        result.putArray("characters").add(person("apprentice", "阿禾", "wharf")).add(person("boatman", "陈舟", "wharf"));
        return result;
    }
    private static ObjectNode cargoContribution(String location) {
        ObjectNode result = JSON.createObjectNode();
        ObjectNode fact = result.putArray("facts").addObject().put("id", "cargo-location").put("subject", "shipment")
            .put("predicate", "location").put("value", location).put("mode", "state");
        fact.putArray("knownBy").add("boatman"); fact.putArray("observableAt").add("warehouse").add("forge");
        return result;
    }
    private static ObjectNode place(String id, String name) { return JSON.createObjectNode().put("id", id).put("name", name); }
    private static ObjectNode person(String id, String name, String location) {
        ObjectNode person = JSON.createObjectNode().put("id", id).put("name", name).put("place", location);
        person.putObject("publicProfile"); person.putObject("privateProfile").put("intent", "查明货物所在位置"); return person;
    }
    private static ObjectNode action(WorldStore world, String requestId) throws Exception {
        return JSON.createObjectNode().put("schemaVersion", 1).put("worldId", world.inspect().path("worldId").asText())
            .put("requestId", requestId).put("expectedRevision", world.inspect().path("revision").asInt()).put("actor", "apprentice");
    }
    private static JsonNode fact(JsonNode draft, String id) { return byId(draft.path("seed").path("facts"), id); }
    private static JsonNode byId(JsonNode values, String id) {
        for (JsonNode row : values) if (row.path("id").asText().equals(id)) return row;
        throw new AssertionError("缺少资料：" + id);
    }
    private static JsonNode evidence(JsonNode draft, String id) {
        for (JsonNode row : draft.path("construction").path("evidence")) if (row.path("moduleId").asText().equals(id)) return row;
        throw new AssertionError("缺少贡献证据：" + id);
    }
    private static JsonNode knowledge(JsonNode view, String factId) {
        for (JsonNode row : view.path("knowledge")) if (row.path("factId").asText().equals(factId)) return row;
        throw new AssertionError("缺少认识：" + factId);
    }
    private static boolean hasKnowledge(JsonNode view, String factId) {
        for (JsonNode row : view.path("knowledge")) if (row.path("factId").asText().equals(factId)) return true;
        return false;
    }
    private static Path nextPath(String label) { return testRoot.resolve(label + "-" + ++fixtureNumber + ".json"); }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("有界等待超时"); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException("等待被中断", failure); }
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
    private static void equal(Object actual, Object expected, String why) {
        boolean same = actual instanceof JsonNode a && expected instanceof JsonNode b ? Protocol.canonical(a).equals(Protocol.canonical(b))
            : java.util.Objects.equals(actual, expected);
        check(same, why + "；预期=" + expected + "；实际=" + actual);
    }
    private static void check(boolean condition, String why) { if (!condition) throw new AssertionError(why); }
    @FunctionalInterface private interface Checked { void run() throws Exception; }
    @FunctionalInterface private interface Construct { ObjectNode apply(WorldConstructor.Context context); }
}
