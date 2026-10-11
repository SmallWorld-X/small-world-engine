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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static creativeworld.ExperienceComponent.Stage;

/** 行为版本、旧历史兼容与恢复边界；只用本地结构化策略，不调用模型。 */
public final class ModuleVersionCheck {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<ObjectNode> RESULTS = new ArrayList<>();
    private static final String MODULE = "versioned-ask";
    private static Path testRoot;
    private static int sequence;

    public static void main(String[] args) throws Exception {
        testRoot = Files.createTempDirectory("creative-world-module-version-check-");

        run("旧模块未声明版本仍固定为1，旧调用产生有来源的消息", () -> {
            Fixture f = fresh(); AtomicInteger calls = new AtomicInteger();
            ActionModule legacy = new ActionModule() {
                public String id() { return MODULE; }
                public JsonNode propose(JsonNode view, JsonNode input) { calls.incrementAndGet(); return askProposal(view, input, "旧版默认策略"); }
            };
            equal(legacy.version(), "1", "接口默认版本"); f.store.register(legacy);
            JsonNode receipt = f.store.step(MODULE, input(f, "legacy-default"));
            equal(receipt.path("revision").asInt(), 1, "只提交一次");
            equal(calls.get(), 1, "旧模块实际执行");
            assertMessage(f); equal(submissionVersion(lastEvent(f)), "1", "未声明版号对应版本1");
        });

        run("同标识不同版本可并存，重复组合与无效版本不能注册", () -> {
            Fixture f = fresh(); f.store.register(ask("1", new AtomicInteger())); f.store.register(ask("2", new AtomicInteger()));
            rejectUnchanged(f, () -> f.store.register(ask("1", new AtomicInteger())));
            for (String bad : new String[] {null, "", " ", "1\n2", "1\r2"}) {
                rejectUnchanged(f, () -> f.store.register(ask(bad, new AtomicInteger())));
                rejectUnchanged(f, () -> f.store.step(MODULE, bad, input(f, "invalid-version")));
            }
            equal(f.store.inspect().path("revision").asInt(), 0, "注册和拒绝均不是世界事件");
        });

        run("旧调用后显式版本1复用回执，不改旧submission或重复运行", () -> {
            Fixture f = fresh(); AtomicInteger calls = new AtomicInteger(); f.store.register(ask("1", calls));
            ObjectNode input = input(f, "implicit-then-explicit"); JsonNode first = f.store.step(MODULE, input);
            byte[] bytes = Files.readAllBytes(f.path); JsonNode history = f.store.history().deepCopy();
            equal(f.store.step(MODULE, "1", input), first, "显式1与旧调用相同");
            equal(f.store.receiptFor(MODULE, input), first, "旧查询返回正式回执");
            equal(f.store.receiptFor(MODULE, "1", input), first, "显式1查询返回同回执");
            equal(f.store.propose(MODULE, "1", input).path("status").asText(), "alreadyCommitted", "显式准备识别旧提交");
            equal(calls.get(), 1, "没有再次执行旧模块"); equal(f.store.history(), history, "没有改写旧submission");
            check(Arrays.equals(bytes, Files.readAllBytes(f.path)), "重复调用后存档字节不变");
        });

        run("显式版本1先提交后旧入口仍等价，显式元数据保留", () -> {
            Fixture f = fresh(); AtomicInteger calls = new AtomicInteger(); f.store.register(ask("1", calls));
            ObjectNode input = input(f, "explicit-then-implicit"); JsonNode first = f.store.step(MODULE, "1", input);
            equal(lastEvent(f).path("submission").path("moduleVersion").asText(), "1", "显式版号进入事件");
            byte[] bytes = Files.readAllBytes(f.path);
            equal(f.store.step(MODULE, input), first, "旧入口能识别显式1");
            equal(f.store.receiptFor(MODULE, input), first, "旧查询也能识别显式1");
            equal(calls.get(), 1, "不重复推导"); check(Arrays.equals(bytes, Files.readAllBytes(f.path)), "旧入口不改显式历史");
        });

        run("版本2独立执行并持久化，重开恢复不要求重新运行实现", () -> {
            Fixture f = fresh(); AtomicInteger one = new AtomicInteger(), two = new AtomicInteger();
            f.store.register(ask("1", one)); f.store.register(ask("2", two));
            f.store.step(MODULE, input(f, "first-one"));
            ObjectNode second = input(f, "then-two"); JsonNode receipt = f.store.step(MODULE, "2", second);
            equal(lastEvent(f).path("submission").path("moduleVersion").asText(), "2", "版本2写入正式事件");
            equal(lastEvent(f).path("proposal").path("reason").asText(), "版本2的询问策略", "实际产物来自版本2");
            equal(one.get(), 1, "版本1只执行自己的请求"); equal(two.get(), 1, "版本2只执行自己的请求");
            WorldStore reopened = new WorldStore(f.path); equal(reopened.history(), f.store.history(), "不用扩展实现也能重放历史");
            equal(reopened.receiptFor(MODULE, "2", second), receipt, "未注册实现仍可查正式回执"); assertMessage(f);
        });

        run("保存的旧版prepared在仅注册2的新宿主提交，不改用新策略", () -> {
            for (boolean explicit : new boolean[] {false, true}) {
                Fixture f = fresh(); AtomicInteger one = new AtomicInteger(), two = new AtomicInteger(); f.store.register(ask("1", one));
                ObjectNode input = input(f, explicit ? "saved-explicit-one" : "saved-legacy-one");
                JsonNode prepared = explicit ? f.store.propose(MODULE, "1", input) : f.store.propose(MODULE, input);
                equal(f.store.inspect().path("revision").asInt(), 0, "准备不提交");
                Path preparedPath = next("prepared"); JSON.writeValue(preparedPath.toFile(), prepared);
                WorldStore reopened = new WorldStore(f.path); reopened.register(ask("2", two));
                JsonNode receipt = reopened.commitPrepared(JSON.readTree(preparedPath.toFile()));
                equal(one.get(), 1, "旧版本只在准备时运行"); equal(two.get(), 0, "恢复不能改用版本2");
                equal(receipt.path("revision").asInt(), 1, "旧提案正常提交");
                equal(reopened.receiptFor(MODULE, input), receipt, "无旧实现也能查询旧回执");
                equal(reopened.receiptFor(MODULE, "1", input), receipt, "显式1查询仍等价");
                byte[] bytes = Files.readAllBytes(f.path); equal(reopened.commitPrepared(prepared), receipt, "准备包重放只返回原回执");
                check(Arrays.equals(bytes, Files.readAllBytes(f.path)), "重放不增加事件"); assertMessage(f);
            }
        });

        run("尚未准备的新请求缺少版本1时明确拒绝，不退到版本2", () -> {
            Fixture f = fresh(); AtomicInteger two = new AtomicInteger(); f.store.register(ask("2", two));
            rejectUnchanged(f, () -> f.store.step(MODULE, input(f, "missing-default")));
            rejectUnchanged(f, () -> f.store.propose(MODULE, "1", input(f, "missing-explicit")));
            rejectUnchanged(f, () -> f.store.propose(MODULE, "9", input(f, "missing-nine")));
            equal(two.get(), 0, "其他已注册版本不能代替缺失版本");
            check(f.store.receiptFor(MODULE, "1", input(f, "no-event")) == null, "未提交查询不会推导或产生假回执");
        });

        run("版本2不能冒领版本1请求，查询和准备包替换均不改世界", () -> {
            Fixture f = fresh(); AtomicInteger two = new AtomicInteger(); f.store.register(ask("1", new AtomicInteger())); f.store.register(ask("2", two));
            ObjectNode input = input(f, "not-cross-version"); JsonNode prepared = f.store.propose(MODULE, input); f.store.commitPrepared(prepared);
            rejectUnchanged(f, () -> f.store.step(MODULE, "2", input));
            rejectUnchanged(f, () -> f.store.propose(MODULE, "2", input));
            rejectUnchanged(f, () -> f.store.receiptFor(MODULE, "2", input));
            ObjectNode changed = prepared.deepCopy(); ((ObjectNode) changed.path("submission")).put("moduleVersion", "2");
            rejectUnchanged(f, () -> f.store.commitPrepared(changed));
            equal(two.get(), 0, "冲突版本不能运行来冒领旧请求");
        });

        run("历史中的无效版本拒绝恢复，旧submission无字段仍可重放", () -> {
            Fixture f = fresh(); f.store.register(ask("1", new AtomicInteger())); ObjectNode input = input(f, "history-one");
            f.store.step(MODULE, input); ObjectNode saved = (ObjectNode) JSON.readTree(f.path.toFile());
            check(!saved.path("events").get(0).path("submission").has("moduleVersion"), "旧调用保持原存档形状");
            equal(new WorldStore(f.path).history(), f.store.history(), "旧形状重放有效");
            for (JsonNode invalid : List.of(JSON.getNodeFactory().textNode(""), JSON.getNodeFactory().textNode("1\n2"),
                    JSON.getNodeFactory().numberNode(2), JSON.getNodeFactory().nullNode())) {
                ObjectNode changed = saved.deepCopy(); ((ObjectNode) changed.path("events").get(0).path("submission")).set("moduleVersion", invalid);
                Path path = next("bad-history-version"); JSON.writeValue(path.toFile(), changed);
                rejects(() -> new WorldStore(path).inspect());
            }
        });

        run("注册后或推导中改变模块身份均拒绝，不落账", () -> {
            for (boolean during : new boolean[] {false, true}) {
                Fixture f = fresh(); AtomicReference<String> version = new AtomicReference<>("1"); AtomicInteger calls = new AtomicInteger();
                f.store.register(new ActionModule() {
                    public String id() { return MODULE; }
                    public String version() { return version.get(); }
                    public JsonNode propose(JsonNode view, JsonNode input) {
                        calls.incrementAndGet(); if (during) version.set("2"); return askProposal(view, input, "改变身份的实现");
                    }
                });
                if (!during) version.set("2");
                rejectUnchanged(f, () -> f.store.step(MODULE, "1", input(f, "mutating-identity")));
                equal(calls.get(), during ? 1 : 0, "改变注册身份不能被当作合法版本");
            }
        });

        run("锁外等待时参数被调用者改写，仍只使用已固定版本及原输入", () -> {
            Fixture f = fresh(); CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            AtomicInteger two = new AtomicInteger(); AtomicReference<JsonNode> seen = new AtomicReference<>();
            f.store.register(new ActionModule() {
                public String id() { return MODULE; }
                public JsonNode propose(JsonNode view, JsonNode input) {
                    entered.countDown(); await(release); seen.set(input.deepCopy()); return askProposal(view, input, "冻结的版本1策略");
                }
            });
            f.store.register(ask("2", two)); ObjectNode input = input(f, "frozen-version"), original = input.deepCopy();
            ExecutorService worker = Executors.newSingleThreadExecutor();
            try {
                Future<JsonNode> result = worker.submit(() -> f.store.step(MODULE, "1", input));
                check(entered.await(5, TimeUnit.SECONDS), "行为进入等待");
                input.put("worldId", "changed-world").put("actor", "@author").put("target", "apprentice");
                release.countDown(); equal(result.get(5, TimeUnit.SECONDS).path("revision").asInt(), 1, "原行为完成");
                equal(seen.get(), original, "回调输入已冻结"); equal(two.get(), 0, "等待时不能换到另一版本");
                equal(lastEvent(f).path("submission").path("input"), original, "历史保存原参数"); assertMessage(f);
            } finally { release.countDown(); worker.shutdownNow(); check(worker.awaitTermination(5, TimeUnit.SECONDS), "等待线程退出"); }
        });

        run("演绎候选明确选择版本2，实际行为及恢复均绑定该版本", () -> {
            Fixture f = fresh(); AtomicInteger one = new AtomicInteger(), two = new AtomicInteger();
            f.store.register(ask("1", one)); f.store.register(ask("2", two));
            ObjectNode request = request(f, "experience-two", "2"); Path path = next("experience");
            ObjectNode record = new ExperienceEngine().run(f.store, request, path);
            equal(record.path("status").asText(), "completed", "版本2演绎完成");
            equal(action(record).path("moduleVersion").asText(), "2", "转换保留选择版本");
            equal(lastEvent(f).path("submission").path("moduleVersion").asText(), "2", "真实提交也是版本2");
            equal(one.get(), 0, "不误用默认版本"); equal(two.get(), 1, "只调用版本2");
            byte[] bytes = Files.readAllBytes(f.path), recordBytes = Files.readAllBytes(path);
            equal(new ExperienceEngine().run(new WorldStore(f.path), request, path), record, "完整恢复不依赖扩展实现");
            check(Arrays.equals(bytes, Files.readAllBytes(f.path)) && Arrays.equals(recordBytes, Files.readAllBytes(path)), "重读不写事件或改旧记录");
        });

        run("选择后不能把版本1偷换成2，省略版本与显式1则相同", () -> {
            for (String changedVersion : new String[] {"2", null}) {
                Fixture f = fresh(); AtomicInteger one = new AtomicInteger(), two = new AtomicInteger();
                f.store.register(ask("1", one)); f.store.register(ask("2", two)); ObjectNode request = request(f, "resolve-version-change", "1");
                ExperienceEngine engine = new ExperienceEngine(); String id = "resolve-version-rewrite";
                engine.register(resolveRewrite(id, changedVersion)); component(request, Stage.RESOLVE).put("id", id);
                Path path = next("resolve-version");
                if (changedVersion != null) {
                    rejectUnchanged(f, () -> engine.run(f.store, request, path));
                    equal(one.get() + two.get(), 0, "非法换版本在行为推导前拒绝");
                    equal(JSON.readTree(path.toFile()).path("status").asText(), "failed", "失败原因保留");
                } else {
                    ObjectNode record = engine.run(f.store, request, path);
                    equal(record.path("status").asText(), "completed", "省略字段等价版本1");
                    equal(one.get(), 1, "等价版本实际用1"); equal(two.get(), 0, "不使用2");
                    equal(submissionVersion(lastEvent(f)), "1", "提交仍固定1");
                }
            }
        });

        run("旧演绎未提交窗口用保存的版本1提案恢复，仅注册2不重新推导", () -> {
            for (boolean explicit : new boolean[] {false, true}) {
                Pending pending = pending(explicit); AtomicInteger two = new AtomicInteger(); WorldStore reopened = new WorldStore(pending.fixture.path);
                reopened.register(ask("2", two));
                ObjectNode record = new ExperienceEngine().run(reopened, pending.request, pending.path);
                equal(record.path("status").asText(), "completed", "已保存旧提案可以恢复");
                equal(two.get(), 0, "恢复不调用替代版本");
                equal(reopened.inspect().path("revision").asInt(), 1, "恢复只提交保存的一次行为");
                equal(submissionVersion(lastEvent(pending.fixture)), "1", "旧演绎保持原行为版本");
                equal(lastEvent(pending.fixture).path("proposal").path("reason").asText(), "版本1的询问策略", "原策略产物没有重算");
            }
        });

        run("恢复记录的prepared被换成版本2时先拒绝，正式世界保持未提交", () -> {
            Pending pending = pending(true); ObjectNode saved = (ObjectNode) JSON.readTree(pending.path.toFile());
            ((ObjectNode) saved.path("prepared").path("submission")).put("moduleVersion", "2"); JSON.writeValue(pending.path.toFile(), saved);
            AtomicInteger two = new AtomicInteger(); pending.fixture.store.register(ask("2", two));
            rejectUnchanged(pending.fixture, () -> new ExperienceEngine().run(pending.fixture.store, pending.request, pending.path));
            equal(two.get(), 0, "不为了改过的准备包运行版本2"); equal(pending.fixture.store.inspect().path("revision").asInt(), 0, "没有首次偷换落账");
        });

        run("演绎组件注册后标识版本或阶段漂移均拒绝，原身份失败记录仍能恢复", () -> {
            for (String field : List.of("id", "version", "stage")) {
                Fixture f = fresh(); f.store.register(ask("1", new AtomicInteger()));
                MutableComponent drift = new MutableComponent("drift-preparation", Stage.PREPARE, null);
                ExperienceEngine engine = replacing(drift); ObjectNode request = request(f, "registered-component-" + field, "1");
                component(request, Stage.PREPARE).put("id", drift.originalId); Path path = next("registered-component");
                drift.change(field);
                rejectUnchanged(f, () -> engine.run(f.store, request, path));
                equal(drift.calls.get(), 0, "漂移组件不得进入回调");
                assertFailedBinding(path, Stage.PREPARE, drift.originalId);
                ExperienceEngine legal = replacing(stable(drift.originalId, Stage.PREPARE));
                equal(legal.run(f.store, request, path).path("status").asText(), "completed", "同原身份合法实现恢复失败阶段");
                equal(f.store.inspect().path("revision").asInt(), 1, "恢复只提交一个实际事件"); assertMessage(f);
            }
        });

        run("演绎回调内改变标识版本或阶段均拒绝，不保存伪完成身份", () -> {
            for (String field : List.of("id", "version", "stage")) {
                Fixture f = fresh(); f.store.register(ask("1", new AtomicInteger()));
                MutableComponent drift = new MutableComponent("callback-drift-preparation", Stage.PREPARE, field);
                ExperienceEngine engine = replacing(drift); ObjectNode request = request(f, "callback-component-" + field, "1");
                component(request, Stage.PREPARE).put("id", drift.originalId); Path path = next("callback-component");
                rejectUnchanged(f, () -> engine.run(f.store, request, path));
                equal(drift.calls.get(), 1, "只运行了发生漂移的回调");
                assertFailedBinding(path, Stage.PREPARE, drift.originalId);
                ObjectNode recovered = replacing(stable(drift.originalId, Stage.PREPARE)).run(f.store, request, path);
                equal(recovered.path("status").asText(), "completed", "回调漂移不破坏同版恢复路径");
                for (JsonNode row : recovered.path("stages")) if (row.path("stage").asText().equals("prepare")) {
                    equal(row.path("componentId").asText(), drift.originalId, "失败和重试记录均为原标识");
                    equal(row.path("componentVersion").asText(), "1", "失败和重试记录均为原版本");
                }
                equal(f.store.inspect().path("revision").asInt(), 1, "失败窗口没有留下提前事件");
            }
        });

        run("反馈组件在提交前已漂移时先拒绝，同版恢复才提交", () -> {
            Fixture f = fresh(); AtomicInteger behaviorCalls = new AtomicInteger(); f.store.register(ask("1", behaviorCalls));
            MutableComponent feedback = new MutableComponent("drift-feedback", Stage.FEEDBACK, null);
            ExperienceComponent baseResolve = defaultComponent(Stage.RESOLVE); AtomicInteger resolves = new AtomicInteger();
            ExperienceComponent trigger = new ExperienceComponent() {
                public String id() { return "drift-trigger-resolve"; }
                public String version() { return "1"; }
                public Stage stage() { return Stage.RESOLVE; }
                public ObjectNode run(Context context) { resolves.incrementAndGet(); ObjectNode output = baseResolve.run(context); feedback.change("version"); return output; }
            };
            ExperienceEngine engine = new ExperienceEngine(false);
            for (ExperienceComponent base : BuiltinExperienceComponents.all())
                engine.register(base.stage() == Stage.RESOLVE ? trigger : base.stage() == Stage.FEEDBACK ? feedback : base);
            ObjectNode request = request(f, "feedback-before-commit", "1");
            component(request, Stage.RESOLVE).put("id", trigger.id()); component(request, Stage.FEEDBACK).put("id", feedback.originalId);
            Path path = next("feedback-before-commit"); rejectUnchanged(f, () -> engine.run(f.store, request, path));
            equal(behaviorCalls.get(), 0, "反馈身份检查应先于准备行为及正式提交");
            equal(feedback.calls.get(), 0, "漂移反馈不进入回调"); assertFailedBinding(path, Stage.FEEDBACK, feedback.originalId);
            equal(resolves.get(), 1, "前四阶段实际已经走完");
            ExperienceEngine legal = new ExperienceEngine(false);
            for (ExperienceComponent base : BuiltinExperienceComponents.all())
                legal.register(base.stage() == Stage.RESOLVE ? stable(trigger.id(), Stage.RESOLVE) : base.stage() == Stage.FEEDBACK ? stable(feedback.originalId, Stage.FEEDBACK) : base);
            ObjectNode recovered = legal.run(f.store, request, path);
            equal(recovered.path("status").asText(), "completed", "合法反馈实现恢复原尝试");
            equal(behaviorCalls.get(), 1, "恢复后才首次运行原行为"); equal(resolves.get(), 1, "已存resolve不再次执行");
            equal(f.store.inspect().path("revision").asInt(), 1, "恢复只形成一次正式事件");
        });

        run("已完成阶段恢复不调用或校验漂移实现，只恢复尚未完成阶段", () -> {
            Fixture f = fresh(); f.store.register(ask("1", new AtomicInteger()));
            MutableComponent preparation = new MutableComponent("cached-preparation", Stage.PREPARE, null);
            ExperienceComponent baseCandidates = defaultComponent(Stage.CANDIDATES); AtomicInteger candidateCalls = new AtomicInteger();
            ExperienceComponent retry = new ExperienceComponent() {
                public String id() { return "retry-candidates"; }
                public String version() { return "1"; }
                public Stage stage() { return Stage.CANDIDATES; }
                public ObjectNode run(Context context) {
                    if (candidateCalls.incrementAndGet() == 1) throw new IllegalStateException("一次可恢复的候选失败");
                    return baseCandidates.run(context);
                }
            };
            ExperienceEngine engine = new ExperienceEngine(false);
            for (ExperienceComponent base : BuiltinExperienceComponents.all())
                engine.register(base.stage() == Stage.PREPARE ? preparation : base.stage() == Stage.CANDIDATES ? retry : base);
            ObjectNode request = request(f, "cached-component-recovery", "1");
            component(request, Stage.PREPARE).put("id", preparation.originalId); component(request, Stage.CANDIDATES).put("id", retry.id());
            Path path = next("cached-component"); rejectUnchanged(f, () -> engine.run(f.store, request, path));
            equal(preparation.calls.get(), 1, "prepare已经实际完成"); assertFailedBinding(path, Stage.CANDIDATES, retry.id());
            preparation.change("id"); preparation.change("version"); preparation.change("stage");
            ObjectNode recovered = engine.run(f.store, request, path);
            equal(recovered.path("status").asText(), "completed", "已存输出按冻结合同恢复");
            equal(preparation.calls.get(), 1, "已完成prepare不再调用"); equal(candidateCalls.get(), 2, "只重试失败候选");
            equal(f.store.inspect().path("revision").asInt(), 1, "恢复正常形成一个事件");
        });

        long passed = RESULTS.stream().filter(row -> row.path("passed").asBoolean()).count(); ObjectNode report = JSON.createObjectNode();
        report.put("checks", RESULTS.size()).put("passed", passed).put("failed", RESULTS.size() - passed).set("results", JSON.valueToTree(RESULTS));
        report.put("actualModelCalled", false).put("formalServiceUsed", false).put("proseQualityEvaluated", false);
        report.put("evidenceLimit", "仅核对本地行为版本、旧协议等价、参数冻结和事务/演绎恢复；不证明模型调用、心理推导、代码签名或小说质量。");
        report.put("isolatedTestDirectory", testRoot.toString());
        if (args.length > 1) report.put("examplesDirectory", Path.of(args[1]).toAbsolutePath().toString());
        if (args.length > 0) { Path output = Path.of(args[0]).toAbsolutePath(); Files.createDirectories(output.getParent()); JSON.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report); }
        System.out.println(JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report)); if (passed != RESULTS.size()) System.exit(1);
    }

    private record Fixture(Path path, WorldStore store) {}
    private record Pending(Fixture fixture, ObjectNode request, Path path) {}
    private static Path next(String kind) { return testRoot.resolve(kind + "-" + ++sequence + ".json"); }
    private static Fixture fresh() throws Exception {
        ObjectNode seed = JSON.createObjectNode().put("schemaVersion", 1).put("worldId", "module-version-town").put("title", "行为版本的小世界");
        seed.putArray("places").addObject().put("id", "wharf").put("name", "南码头");
        seed.withArray("places").addObject().put("id", "warehouse").put("name", "货栈");
        ArrayNode people = seed.putArray("characters");
        for (String id : List.of("apprentice", "boatman")) {
            ObjectNode person = people.addObject().put("id", id).put("name", id.equals("apprentice") ? "阿禾" : "陈舟").put("place", "wharf");
            person.putObject("publicProfile").put("occupation", id.equals("apprentice") ? "学徒" : "船工");
            person.putObject("privateProfile").put("intent", id.equals("apprentice") ? "问清铁料箱的位置" : "交接货物");
        }
        ObjectNode fact = seed.putArray("facts").addObject().put("id", "cargo-location").put("subject", "iron-cargo").put("predicate", "location").put("value", "warehouse").put("mode", "state");
        fact.putArray("knownBy").add("boatman"); fact.putArray("observableAt").add("warehouse");
        Path path = next("world"); WorldStore store = new WorldStore(path); store.initialize(seed); return new Fixture(path, store);
    }
    private static ObjectNode input(Fixture f, String id) throws Exception {
        return JSON.createObjectNode().put("schemaVersion", 1).put("worldId", f.store.inspect().path("worldId").asText())
            .put("requestId", id).put("expectedRevision", f.store.inspect().path("revision").asInt()).put("actor", "apprentice")
            .put("target", "boatman").put("factId", "cargo-location");
    }
    private static ActionModule ask(String version, AtomicInteger calls) {
        return new ActionModule() {
            public String id() { return MODULE; }
            public String version() { return version; }
            public JsonNode propose(JsonNode view, JsonNode input) { calls.incrementAndGet(); return askProposal(view, input, "版本" + version + "的询问策略"); }
        };
    }
    private static ObjectNode askProposal(JsonNode view, JsonNode input, String reason) {
        check(!view.has("facts") && !view.has("characters"), "版本策略不能收到作者全量世界");
        check(view.path("self").path("id").asText().equals("apprentice"), "版本策略只收到本人投影");
        ObjectNode proposal = JSON.createObjectNode();
        for (String field : List.of("schemaVersion", "worldId", "requestId", "expectedRevision", "actor")) proposal.set(field, input.path(field).deepCopy());
        proposal.put("reason", reason).putArray("effects").addObject().put("type", "knowledge.learn").put("personId", input.path("actor").asText())
            .put("factId", input.path("factId").asText()).put("sourcePersonId", input.path("target").asText());
        return proposal;
    }
    private static ObjectNode request(Fixture f, String id, String moduleVersion) throws Exception {
        ObjectNode request = input(f, id); request.remove(List.of("target", "factId")); request.put("intent", "依据陈舟的消息查问铁料箱");
        ArrayNode components = request.putArray("components");
        for (ExperienceComponent component : BuiltinExperienceComponents.all())
            components.addObject().put("stage", component.stage().key()).put("id", component.id()).put("version", component.version());
        ObjectNode scenario = request.putObject("scenario");
        scenario.putArray("requiredEvidence").addObject().put("path", "/nearby/0/id").put("label", "询问对象在同地").put("equals", "boatman");
        ObjectNode candidate = scenario.putArray("candidates").addObject().put("id", "ask-boatman").put("module", MODULE).put("description", "向陈舟询问铁料箱").put("priority", 1);
        if (moduleVersion != null) candidate.put("moduleVersion", moduleVersion);
        candidate.putObject("arguments").put("target", "boatman").put("factId", "cargo-location");
        candidate.putArray("evidence").addObject().put("path", "/nearby/0/id").put("label", "陈舟可当面交流").put("equals", "boatman");
        scenario.putArray("remainingGaps").add("尚未现场观察，更没有运输"); return request;
    }
    private static ExperienceComponent resolveRewrite(String id, String changedVersion) {
        ExperienceComponent delegate = BuiltinExperienceComponents.all().stream().filter(c -> c.stage() == Stage.RESOLVE).findFirst().orElseThrow();
        return new ExperienceComponent() {
            public String id() { return id; }
            public String version() { return "1"; }
            public Stage stage() { return Stage.RESOLVE; }
            public ObjectNode run(Context context) {
                ObjectNode output = delegate.run(context); ObjectNode action = (ObjectNode) output.path("action");
                if (changedVersion == null) action.remove("moduleVersion"); else action.put("moduleVersion", changedVersion);
                return output;
            }
        };
    }
    private static ExperienceComponent defaultComponent(Stage stage) {
        return BuiltinExperienceComponents.all().stream().filter(component -> component.stage() == stage).findFirst().orElseThrow();
    }
    private static ExperienceComponent stable(String id, Stage stage) {
        ExperienceComponent delegate = defaultComponent(stage);
        return new ExperienceComponent() {
            public String id() { return id; }
            public String version() { return "1"; }
            public Stage stage() { return stage; }
            public ObjectNode run(Context context) { return delegate.run(context); }
        };
    }
    private static ExperienceEngine replacing(ExperienceComponent replacement) {
        ExperienceEngine engine = new ExperienceEngine(false);
        for (ExperienceComponent base : BuiltinExperienceComponents.all()) engine.register(base.stage() == replacement.stage() ? replacement : base);
        return engine;
    }
    private static final class MutableComponent implements ExperienceComponent {
        private final String originalId;
        private final Stage originalStage;
        private final String during;
        private final AtomicReference<String> currentId, currentVersion = new AtomicReference<>("1");
        private final AtomicReference<Stage> currentStage;
        private final AtomicInteger calls = new AtomicInteger();
        private MutableComponent(String id, Stage stage, String during) {
            originalId = id; originalStage = stage; this.during = during; currentId = new AtomicReference<>(id); currentStage = new AtomicReference<>(stage);
        }
        public String id() { return currentId.get(); }
        public String version() { return currentVersion.get(); }
        public Stage stage() { return currentStage.get(); }
        public ObjectNode run(Context context) {
            calls.incrementAndGet(); ObjectNode output = defaultComponent(originalStage).run(context);
            if (during != null) change(during); return output;
        }
        private void change(String field) {
            switch (field) {
                case "id" -> currentId.set(originalId + "-changed");
                case "version" -> currentVersion.set("2");
                case "stage" -> currentStage.set(originalStage == Stage.PREPARE ? Stage.CANDIDATES : Stage.PREPARE);
                default -> throw new AssertionError("未知漂移字段：" + field);
            }
        }
    }
    private static void assertFailedBinding(Path path, Stage stage, String id) throws Exception {
        JsonNode record = JSON.readTree(path.toFile()); equal(record.path("status").asText(), "failed", "未提交失败记录");
        for (JsonNode row : record.path("stages")) if (row.path("status").asText().equals("failed") && row.path("stage").asText().equals(stage.key())) {
            equal(row.path("componentId").asText(), id, "失败阶段保留注册时标识");
            equal(row.path("componentVersion").asText(), "1", "失败阶段保留注册时版本"); return;
        }
        throw new AssertionError("缺少原声明阶段的失败记录：" + stage);
    }
    private static Pending pending(boolean explicit) throws Exception {
        Fixture source = fresh(); source.store.register(ask("1", new AtomicInteger()));
        ObjectNode request = request(source, explicit ? "pending-explicit-one" : "pending-old-one", explicit ? "1" : null);
        ObjectNode completed = new ExperienceEngine().run(source.store, request, next("source-record"));
        Fixture destination = fresh(); ObjectNode record = completed.deepCopy();
        // 构造受控的“前四阶段和prepared已保存、提交尚未发生”窗口，不宣称真实断电试验。
        record.put("worldFile", destination.store.path().toString()).put("status", "running"); record.remove(List.of("receipt", "finalView", "error"));
        ((ArrayNode) record.path("stages")).remove(4);
        if (!explicit) ((ObjectNode) record.path("prepared").path("submission")).remove("moduleVersion");
        Path path = next("pending-record"); JSON.writeValue(path.toFile(), record); return new Pending(destination, request, path);
    }
    private static ObjectNode component(ObjectNode request, Stage stage) {
        for (JsonNode row : request.path("components")) if (row.path("stage").asText().equals(stage.key())) return (ObjectNode) row;
        throw new AssertionError("缺少阶段：" + stage);
    }
    private static JsonNode action(JsonNode record) {
        for (JsonNode row : record.path("stages")) if (row.path("stage").asText().equals("resolve") && row.path("status").asText().equals("completed")) return row.path("output").path("action");
        throw new AssertionError("没有实际行动");
    }
    private static JsonNode lastEvent(Fixture f) throws Exception { JsonNode events = f.store.history().path("events"); return events.get(events.size() - 1); }
    private static String submissionVersion(JsonNode event) { return event.path("submission").path("moduleVersion").asText("1"); }
    private static void assertMessage(Fixture f) throws Exception {
        for (JsonNode known : f.store.view("apprentice").path("knowledge")) if (known.path("factId").asText().equals("cargo-location")) {
            equal(known.path("value").asText(), "warehouse", "实际来源消息"); equal(known.path("source").asText(), "boatman", "来源仍为陈舟"); return;
        }
        throw new AssertionError("没有形成实际认识");
    }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("有界等待超时"); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException("等待被中断", interrupted); }
    }
    private static void rejectUnchanged(Fixture f, Checked action) throws Exception {
        byte[] before = Files.readAllBytes(f.path); JsonNode history = f.store.history().deepCopy(); rejects(action);
        check(Arrays.equals(before, Files.readAllBytes(f.path)), "拒绝后正式文件字节不变"); equal(f.store.history(), history, "拒绝后历史不变");
    }
    private static void rejects(Checked action) throws Exception {
        try { action.run(); } catch (IllegalArgumentException | IllegalStateException | java.io.IOException expected) { return; }
        throw new AssertionError("应拒绝的操作被接受");
    }
    private static void run(String name, Checked action) {
        ObjectNode result = JSON.createObjectNode().put("name", name);
        try { action.run(); result.put("passed", true); }
        catch (Exception | AssertionError failure) { result.put("passed", false).put("error", failure.getClass().getSimpleName() + ": " + failure.getMessage()); }
        RESULTS.add(result);
    }
    private static void equal(Object actual, Object expected, String why) {
        boolean same = actual instanceof JsonNode a && expected instanceof JsonNode b ? Protocol.canonical(a).equals(Protocol.canonical(b)) : java.util.Objects.equals(actual, expected);
        check(same, why + "；预期=" + expected + "；实际=" + actual);
    }
    private static void check(boolean condition, String why) { if (!condition) throw new AssertionError(why); }
    @FunctionalInterface private interface Checked { void run() throws Exception; }
}
