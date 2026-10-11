package creativeworld;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import static creativeworld.ExperienceComponent.Stage;

/** 小而完整骨架的实际事务验收；执行边界不等于社会权力、心理或文学质量。 */
public final class SkeletonCheck {
    private static final ObjectMapper JSON = Protocol.JSON;
    private static final List<ObjectNode> RESULTS = new ArrayList<>();
    private static Path examples, directory;
    private static int number;

    public static void main(String[] args) throws Exception {
        examples = args.length > 1 ? Path.of(args[1]) : Path.of("examples");
        if (!Files.exists(examples.resolve("skeleton/genesis.json"))) examples = Path.of("creative-world-engine/examples");
        directory = Files.createTempDirectory("creative-world-skeleton-");

        run("完整世界的声明执行边界与非信息变化：铁匠消耗并观察木炭", () -> {
            Fixture f = fresh(); JsonNode before = f.world.view("@author").deepCopy();
            equal(before.path("places").size(), 6, "保持完整地点"); equal(before.path("characters").size(), 6, "保持完整人物");
            for (JsonNode fact : before.path("facts")) if (fact.path("mode").asText().equals("state")) {
                int matches = 0; for (JsonNode law : before.path("laws")) if (law.path("kind").asText().equals("writeAccess") && law.path("factId").equals(fact.path("id"))) matches++;
                equal(matches, 1, "每个状态有一条作者明确声明的执行边界");
            }
            JsonNode apprenticeOld = known(f.world.view("apprentice"), "charcoal-quantity").deepCopy();
            ObjectNode consume = input(f, "consume-charcoal.json"); JsonNode receipt = f.world.apply(consume);
            equal(fact(f.world.view("@author"), "charcoal-quantity").path("value").asInt(), 7, "实际资源8变7");
            JsonNode smith = known(f.world.view("smith"), "charcoal-quantity");
            equal(smith.path("value").asInt(), 7, "本人现场清点"); equal(smith.path("source").asText(), "@world", "来自现场");
            equal(smith.path("revision"), receipt.path("revision"), "认识与提交同版");
            equal(known(f.world.view("apprentice"), "charcoal-quantity"), apprenticeOld, "不刷新未在场学徒的旧认识");
            equal(fact(f.world.viewAt("@author", 0), "charcoal-quantity").path("value").asInt(), 8, "旧版本资源保持");
            byte[] bytes = Files.readAllBytes(f.path); equal(new WorldEngine().openWorld(f.path).apply(consume), receipt, "重开重复原输入返回原回执");
            check(Arrays.equals(bytes, Files.readAllBytes(f.path)), "重复不重写世界"); stable(before, f.world.view("@author")); privacy(f, f.world.view("smith"));
        });

        run("收货人可明确决定许可，学徒与保管员不能代替货主", () -> {
            Fixture f = fresh();
            for (String actor : List.of("apprentice", "keeper")) unchangedReject(f, () -> f.world.apply(proposal(f, "no-self-approval-" + actor, actor, change("cargo-pickup-permission", false, true))));
            unchangedReject(f, () -> f.world.apply(proposal(f, "wrong-resource-writer", "apprentice", change("charcoal-quantity", 8, 7))));
            JsonNode keeperOld = known(f.world.view("keeper"), "cargo-pickup-permission").deepCopy();
            f.world.apply(input(f, "grant-permission.json"));
            equal(fact(f.world.view("@author"), "cargo-pickup-permission").path("value").asBoolean(), true, "仅货主smith的明确决定落账");
            equal(known(f.world.view("keeper"), "cargo-pickup-permission"), keeperOld, "改变许可不自动传播给保管员");
            check(!hasKnown(f.world.view("apprentice"), "cargo-pickup-permission"), "学徒不会因世界已有新许可自动知情");
            equal(fact(f.world.view("@author"), "cargo-registration-state").path("value").asText(), "pending", "单项许可没有完成登记");
            equal(fact(f.world.view("@author"), "cargo-location").path("value").asText(), "warehouse", "单项许可不搬货");
            f.world.apply(proposal(f, "author-explicit-correction", "@author", change("cargo-pickup-permission", true, false)));
            equal(fact(f.world.view("@author"), "cargo-pickup-permission").path("value").asBoolean(), false, "作者保留明确状态输入能力");
        });

        run("组合效果不能绕过执行边界、资源法则与固定事实，整包原子拒绝", () -> {
            Fixture f = fresh();
            unchangedReject(f, () -> f.world.apply(proposal(f, "resource-and-permission", "smith", change("charcoal-quantity", 8, 7), change("cargo-registration-state", "pending", "registered"))));
            unchangedReject(f, () -> f.world.apply(proposal(f, "negative-resource", "smith", change("charcoal-quantity", 8, -1), observe("smith", "charcoal-quantity"))));
            unchangedReject(f, () -> f.world.apply(proposal(f, "wrong-observation-place", "smith", change("charcoal-quantity", 8, 7), observe("smith", "cargo-location"))));
            unchangedReject(f, () -> f.world.apply(proposal(f, "fixed-owner", "@author", change("cargo-owner", "smith", "keeper"))));
            unchangedReject(f, () -> f.world.apply(proposal(f, "author-only-population", "keeper", change("dock-population", 12, 13))));
            f.world.register(new ActionModule() {
                public String id() { return "invalid-permission-change"; }
                public JsonNode propose(JsonNode view, JsonNode input) {
                    ObjectNode result = JSON.createObjectNode();
                    for (String field : List.of("schemaVersion", "worldId", "requestId", "expectedRevision", "actor")) result.set(field, input.get(field).deepCopy());
                    result.put("reason", "反例：行为产生未经收货人允许的写入，正式提交必须拒绝"); result.putArray("effects").add(change("cargo-pickup-permission", false, true)); return result;
                }
            });
            unchangedReject(f, () -> f.world.step("invalid-permission-change", action(f, "step-unauthorized-write", "apprentice")));
            byte[] before = Files.readAllBytes(f.path);
            JsonNode prepared = f.world.propose("invalid-permission-change", action(f, "prepared-unauthorized-write", "keeper"));
            equal(prepared.path("status").asText(), "prepared", "保存候选不冒充正式提交"); check(Arrays.equals(before, Files.readAllBytes(f.path)), "准备提案不改世界");
            unchangedReject(f, () -> new WorldStore(f.path).commitPrepared(prepared));
        });

        run("写入法则声明错误在构造时拒绝，旧无声明世界保留原兼容语义", () -> {
            ObjectNode base = seed();
            for (int invalid = 0; invalid < 6; invalid++) {
                ObjectNode bad = base.deepCopy(); ObjectNode law = (ObjectNode) by(bad.path("laws"), "id", "write-charcoal-quantity");
                switch (invalid) {
                    case 0 -> law.put("factId", "cargo-owner");
                    case 1 -> law.putArray("actors").add("@author");
                    case 2 -> law.putArray("actors").add("absent-person");
                    case 3 -> law.putArray("actors").add("smith").add("smith");
                    case 4 -> ((ArrayNode) bad.path("laws")).add(law.deepCopy().put("id", "duplicate-fact-write-law"));
                    case 5 -> law.put("factId", "absent-fact");
                }
                Path path = next("invalid-seed"); rejects(() -> new WorldStore(path).initialize(bad)); check(!Files.exists(path), "无非法正式世界残留");
            }
            ObjectNode old = base.deepCopy(); ArrayNode laws = (ArrayNode) old.path("laws");
            for (int i = laws.size() - 1; i >= 0; i--) if (laws.get(i).path("kind").asText().equals("writeAccess")) laws.remove(i);
            Fixture legacy = fresh(old); legacy.world.apply(proposal(legacy, "legacy-not-business-authorization", "apprentice", change("charcoal-quantity", 8, 7)));
            equal(fact(new WorldStore(legacy.path).view("@author"), "charcoal-quantity").path("value").asInt(), 7, "旧资料未被新守卫改变语义；不称业务授权已验证");
        });

        run("新增具名群体成员在同一事务接入后可独立询问、移动和恢复", () -> {
            Fixture f = fresh(); JsonNode before = f.world.view("@author").deepCopy();
            ObjectNode add = input(f, "add-worker.json"); JsonNode added = f.world.apply(add);
            equal(f.world.view("@author").path("characters").size(), 7, "新增一个人物");
            JsonNode group = by(f.world.view("@author").path("entities"), "id", "dock-workers");
            check(contains(group.path("refs"), "new-porter"), "群体实际登记成员");
            equal(fact(f.world.view("@author"), "dock-population").path("value").asInt(), 12, "展开成员不伪增总人口");
            equal(f.world.viewAt("@author", 0).path("characters").size(), 6, "旧世界人物保持");
            JsonNode ask = f.world.step("ask", input(f, "worker-ask.json"));
            JsonNode message = known(f.world.view("new-porter"), "cargo-registration-state");
            equal(message.path("source").asText(), "keeper", "新增人独立获得来源"); equal(message.path("value").asText(), "pending", "没有凭空登记");
            equal(message.path("revision"), ask.path("revision"), "实际事件生成认识");
            f.world.step("travel", input(f, "worker-travel.json"));
            WorldStore reopened = new WorldEngine().openWorld(f.path);
            equal(reopened.view("new-porter").path("self").path("place").asText(), "wharf", "新增人物按明确直接路线移动并恢复");
            equal(known(reopened.view("new-porter"), "cargo-registration-state"), message, "恢复保留本人消息");
            byte[] bytes = Files.readAllBytes(f.path); equal(reopened.apply(add), added, "恢复新增成员原回执不重登记");
            check(Arrays.equals(bytes, Files.readAllBytes(f.path)), "重复添加不写世界");
            privacy(f, reopened.view("new-porter")); stable(before, reopened.view("@author"));
        });

        run("成员登记缺口、身份错误与超人口组合都不留下半个人物", () -> {
            Fixture f = fresh(); ObjectNode source = input(f, "add-worker.json");
            ObjectNode missingRegistration = source.deepCopy().put("requestId", "missing-group-registration"); ((ArrayNode) missingRegistration.path("effects")).remove(1);
            unchangedReject(f, () -> f.world.apply(missingRegistration));
            for (String invalid : List.of("absent-group", "charcoal-stock", "guild-staff")) {
                ObjectNode bad = source.deepCopy().put("requestId", "wrong-group-" + invalid); ((ObjectNode) bad.path("effects").get(1)).put("groupId", invalid);
                unchangedReject(f, () -> f.world.apply(bad));
            }
            unchangedReject(f, () -> f.world.apply(proposal(f, "existing-member", "@author", member("dock-workers", "boatman"))));
            unchangedReject(f, () -> f.world.apply(proposal(f, "no-person", "@author", member("dock-workers", "absent-person"))));
            ObjectNode actorAdds = source.deepCopy().put("requestId", "ordinary-person-adds").put("actor", "keeper"); unchangedReject(f, () -> f.world.apply(actorAdds));
            ObjectNode tooMany = source.deepCopy().put("requestId", "final-population-check"); ((ArrayNode) tooMany.path("effects")).add(change("dock-population", 12, 1));
            unchangedReject(f, () -> f.world.apply(tooMany));
            f.world.apply(source);
            unchangedReject(f, () -> f.world.apply(proposal(f, "ordinary-group-edit", "boatman", member("dock-workers", "new-porter"))));
            unchangedReject(f, () -> f.world.apply(proposal(f, "duplicate-new-member", "@author", member("dock-workers", "new-porter"))));
        });

        run("共同宿主的五个v2组件与v2行为连续推进、暂停恢复并绑定真实回执", () -> {
            Counters calls = new Counters(); WorldEngine host = probeHost("2", calls); Fixture f = fresh(host, seed());
            ObjectNode request = probeRequest(f, "v2-journey"); ObjectNode original = request.deepCopy(); Path journey = directory.resolve("v2-journey");
            ObjectNode paused = host.pursue(f.world, request, journey, 1);
            equal(paused.path("status").asText(), "paused", "一次预算暂停"); equal(revision(f), 1, "先观察登记");
            ObjectNode done = host.pursue(f.world, request, journey, 8);
            equal(done.path("status").asText(), "satisfied", "两次实际观察后第三步正常满足"); equal(revision(f), 2, "仅两次正式行动");
            equal(done.path("attempts").size(), 3, "包含满意停止步骤"); equal(calls.action.get(), 2, "真正调用v2行为两次");
            for (JsonNode event : f.world.history().path("events")) { equal(event.path("submission").path("module").asText(), "skeleton-observe", "使用扩展模块"); equal(event.path("submission").path("moduleVersion").asText(), "2", "实际事件记录行为版2"); }
            for (String name : List.of("cargo-registration-state", "cargo-seal-status")) equal(known(f.world.view("keeper"), name).path("source").asText(), "@world", "产生本人实际现场认识");
            JsonNode first = JSON.readTree(journey.resolve("step-001.json").toFile());
            for (JsonNode stage : first.path("stages")) equal(stage.path("componentVersion").asText(), "2", "保存每个阶段绑定版2");
            equal(first.path("finalView"), f.world.viewAt("keeper", first.path("receipt").path("revision").asInt()), "反馈与旧回执版一致");
            Counters restoredCalls = new Counters(); WorldEngine reopenedHost = probeHost("2", restoredCalls); WorldStore reopened = reopenedHost.openWorld(f.path); byte[] bytes = Files.readAllBytes(f.path);
            equal(reopenedHost.pursue(reopened, request, journey, 8).path("status").asText(), "satisfied", "同宿主配置重开恢复过程");
            equal(restoredCalls.action.get(), 0, "完成恢复不再执行行为"); equal(restoredCalls.total(), 0, "完成恢复不再运行阶段"); check(Arrays.equals(bytes, Files.readAllBytes(f.path)), "恢复不重复落账");
            equal(request, original, "连续推进不改调用者请求"); privacy(f, first.path("initialView"));
        });

        run("缺失扩展绑定不回退默认，宿主重复与可变身份明确拒绝", () -> {
            Fixture missingStage = fresh(); ObjectNode request = probeRequest(missingStage, "missing-v2-stage");
            unchangedReject(missingStage, () -> new WorldEngine().pursue(missingStage.world, request, directory.resolve("missing-stage"), 8));
            Counters calls = new Counters(); WorldEngine noAction = stageHost("2", calls); noAction.register(probeAction("1", calls)); Fixture missingAction = fresh(noAction, seed());
            unchangedReject(missingAction, () -> noAction.pursue(missingAction.world, probeRequest(missingAction, "missing-v2-action"), directory.resolve("missing-action"), 8));
            equal(calls.action.get(), 0, "有v1也绝不执行代替v2");
            WorldEngine host = new WorldEngine(); ActionModule same = probeAction("2", new Counters()); host.register(same); rejects(() -> host.register(same)); host.register(probeAction("1", new Counters()));
            String[] version = {"2"}; WorldEngine drifting = new WorldEngine().register(new ActionModule() {
                public String id() { return "mutable-host-module"; } public String version() { return version[0]; }
                public JsonNode propose(JsonNode view, JsonNode input) { throw new AssertionError("不得调用漂移模块"); }
            }); version[0] = "3"; rejects(() -> drifting.openWorld(next("drifting-host")));
            WorldEngine colliding = new WorldEngine().register(new ActionModule() {
                public String id() { return "ask"; } public JsonNode propose(JsonNode view, JsonNode input) { throw new AssertionError("不能覆盖默认ask"); }
            }); rejects(() -> colliding.openWorld(next("default-collision")));
        });

        run("共同宿主的构造扩展与拦截快照实际生效，确认后进入同一保存主链", () -> {
            AtomicInteger constructed = new AtomicInteger(), intercepted = new AtomicInteger(); WorldEngine host = new WorldEngine();
            WorldConstructor constructor = new WorldConstructor() {
                public String id() { return "author-source"; } public String version() { return "2"; }
                public String sectionName() { return "authorSource"; } public Set<String> dependencies() { return Set.of("world"); }
                public ObjectNode construct(Context context) {
                    constructed.incrementAndGet(); ObjectNode contribution = JSON.createObjectNode();
                    ObjectNode fact = JSON.createObjectNode().put("id", "skeleton-extension-source").put("subject", "river-town").put("predicate", "skeleton-extension-provenance").put("value", context.section().path("source").asText()).put("mode", "fixed");
                    fact.putArray("knownBy"); contribution.putArray("facts").add(fact);
                    context.seed().put("title", "不能覆盖正式标题"); return contribution;
                }
            };
            host.register(constructor).intercept(new CreationInterceptor() {
                public void after(String moduleId, WorldConstructor.Context context, JsonNode contribution) { intercepted.incrementAndGet(); context.blueprint().put("worldId", "not-authority"); if (contribution.has("facts")) ((ArrayNode) contribution.path("facts")).removeAll(); }
            });
            ObjectNode blueprint = read("genesis.json"); ((ObjectNode) blueprint.path("sections")).putObject("authorSource").put("source", "作者明确选择的构造扩展v2；不是自然语言推导"); ((ArrayNode) blueprint.path("constructors")).addObject().put("id", "author-source").put("version", "2"); ObjectNode original = blueprint.deepCopy();
            ObjectNode draft = host.genesis().build(blueprint); ObjectNode confirmed = host.genesis().confirmSeed(draft);
            equal(constructed.get(), 2, "构造及确认重建实际执行同版"); check(intercepted.get() > 0, "拦截器实际执行");
            equal(fact(confirmed, "skeleton-extension-source").path("value").asText(), original.path("sections").path("authorSource").path("source").asText(), "修改拦截快照未抹除真实贡献");
            equal(blueprint, original, "构造不修改蓝图"); Fixture f = fresh(host, confirmed); equal(revision(f), 0, "确认初始化无伪事件");
            f.world.step("ask", action(f, "constructed-world-ask", "apprentice").put("target", "boatman").put("factId", "cargo-location"));
            equal(host.openWorld(f.path).history().path("events").size(), 1, "扩展构造结果可执行和重开");
            byte[] bytes = Files.readAllBytes(f.path); ObjectNode prep = read("preparation.json"); host.preparation().analyze(f.world, prep); check(Arrays.equals(bytes, Files.readAllBytes(f.path)), "共享准备实例仍只读");
        });

        run("默认宿主入口兼容：依据本人消息和路线完成三步观察而非交付", () -> {
            WorldEngine host = new WorldEngine(); Fixture f = fresh(host, seed()); ObjectNode input = input(f, "apprentice-goal.json");
            ObjectNode result = host.pursue(f.world, input, directory.resolve("default-journey"), 8);
            equal(result.path("status").asText(), "satisfied", "默认目标正常停止");
            List<String> trace = new ArrayList<>(); for (JsonNode event : f.world.history().path("events")) trace.add(event.path("submission").path("module").asText());
            equal(trace, List.of("ask", "travel", "observe"), "默认装配保持原实际行动顺序");
            equal(fact(f.world.view("@author"), "cargo-pickup-permission").path("value").asBoolean(), false, "核实没有自行放行");
            equal(fact(f.world.view("@author"), "cargo-location").path("value").asText(), "warehouse", "没有交付");
            privacy(f, f.world.view("apprentice"));
        });

        run("作者在人展开时补定未记录身份，指定知情并单独揭示，不改旧历史", () -> {
            Fixture f = fresh(); JsonNode before = f.world.view("@author").deepCopy();
            String identityId = "keeper-unfolded-identity", identity = "行会账簿核验人";
            check(!hasKnown(f.world.view("keeper"), identityId), "补定之前本人资料没有这项身份");
            for (JsonNode row : before.path("facts")) check(!row.path("id").asText().equals(identityId), "作者旧资料也未定义这项身份");
            ObjectNode fact = JSON.createObjectNode().put("id", identityId).put("subject", "keeper").put("predicate", "unfolded-identity").put("value", identity).put("mode", "fixed");
            fact.putArray("knownBy").add("keeper"); ObjectNode define = JSON.createObjectNode().put("type", "fact.define"); define.set("fact", fact);
            JsonNode defined = f.world.apply(proposal(f, "author-unfolds-identity", "@author", define));
            JsonNode author = f.world.view("@author"); equal(author.path("characters"), before.path("characters"), "补身份事实不创建替身、不改人物标识姓名形象与经历");
            equal(fact(author, identityId).path("value").asText(), identity, "作者实际看到新身份资料");
            equal(known(f.world.view("keeper"), identityId).path("value").asText(), identity, "knownBy指定本人知情");
            equal(known(f.world.view("keeper"), identityId).path("revision"), defined.path("revision"), "认识从本次定义开始记录");
            for (JsonNode person : author.path("characters")) if (!person.path("id").asText().equals("keeper")) {
                JsonNode view = f.world.view(person.path("id").asText()); check(!hasKnown(view, identityId), "未指定人物不自动知情");
                check(!JSON.writeValueAsString(view).contains(identity), "普通人物可见资料也不泄漏隐藏身份");
            }
            equal(f.world.view("@reader").path("readerKnowledge").size(), 0, "作者定义不等于读者已获揭示");
            ObjectNode reveal = JSON.createObjectNode().put("type", "reader.reveal").put("factId", identityId);
            JsonNode revealed = f.world.apply(proposal(f, "author-reveals-identity-to-reader", "@author", reveal));
            JsonNode reader = by(f.world.view("@reader").path("readerKnowledge"), "factId", identityId);
            equal(reader.path("value").asText(), identity, "单独揭示后读者取得这项身份"); equal(reader.path("revision"), revealed.path("revision"), "读者揭示版本单独保存");
            check(!hasKnown(f.world.view("apprentice"), identityId), "读者知情不让故事人物越过认识边界");
            for (JsonNode row : f.world.viewAt("@author", 0).path("facts")) check(!row.path("id").asText().equals(identityId), "旧作者版本仍未定义身份");
            check(!hasKnown(f.world.viewAt("keeper", 0), identityId), "旧人物版本仍未知");
            equal(f.world.viewAt("@reader", defined.path("revision").asInt()).path("readerKnowledge").size(), 0, "揭示前的读者版本不被回填");
            ObjectNode duplicate = fact.deepCopy().put("id", "duplicate-unfolded-identity").put("value", "另一项相冲突的身份");
            ObjectNode duplicateDefine = JSON.createObjectNode().put("type", "fact.define"); duplicateDefine.set("fact", duplicate);
            unchangedReject(f, () -> f.world.apply(proposal(f, "no-redefinition-of-identity", "@author", duplicateDefine)));
            equal(f.world.history().path("events").size(), 2, "只发生补定与受控揭示，不追加伪造过去的事件");
            equal(new WorldEngine().openWorld(f.path).view("@reader"), f.world.view("@reader"), "重开保留受控揭示结果"); stable(before, f.world.view("@author"));
        });

        int passed = (int) RESULTS.stream().filter(row -> row.path("passed").asBoolean()).count();
        ObjectNode report = JSON.createObjectNode().put("schemaVersion", 1).put("kind", "skeleton-check").put("checks", RESULTS.size()).put("passed", passed).put("failed", RESULTS.size() - passed);
        report.set("results", JSON.valueToTree(RESULTS)); report.put("isolatedTestDirectory", directory.toString()).put("examplesDirectory", examples.toAbsolutePath().toString());
        report.put("evidenceLimit", "本地作者声明的状态执行边界、实际资源事务、成员引用、共同宿主版本恢复、身份资料补定与受控揭示；不证明职务到支配范围的映射、社会权力推导、自然语言解析、追溯补造已发生历史、心理、自动调度、生产完成、货物交付或小说质量。");
        if (args.length > 0) { Path out = Path.of(args[0]).toAbsolutePath(); Files.createDirectories(out.getParent()); JSON.writerWithDefaultPrettyPrinter().writeValue(out.toFile(), report); }
        System.out.println(JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report)); if (passed != RESULTS.size()) System.exit(1);
    }

    private record Fixture(Path path, WorldStore world, String worldId) {}
    private static Fixture fresh() throws Exception { return fresh(new WorldEngine(), seed()); }
    private static Fixture fresh(ObjectNode seed) throws Exception { return fresh(new WorldEngine(), seed); }
    private static Fixture fresh(WorldEngine host, ObjectNode seed) throws Exception { Path path = next("world"); WorldStore world = host.openWorld(path); world.initialize(seed); return new Fixture(path, world, seed.path("worldId").asText()); }
    private static ObjectNode read(String name) throws Exception { return (ObjectNode) JSON.readTree(examples.resolve("skeleton").resolve(name).toFile()); }
    private static ObjectNode seed() throws Exception { return (ObjectNode) new WorldGenesis().build(read("genesis.json")).path("seed").deepCopy(); }
    private static Path next(String name) { return directory.resolve(name + "-" + ++number + ".json"); }
    private static int revision(Fixture f) throws Exception { return f.world.inspect().path("revision").asInt(); }
    private static ObjectNode action(Fixture f, String id, String actor) throws Exception { return JSON.createObjectNode().put("schemaVersion", 1).put("worldId", f.worldId).put("requestId", id).put("expectedRevision", revision(f)).put("actor", actor); }
    private static ObjectNode input(Fixture f, String name) throws Exception { return read(name).put("worldId", f.worldId).put("expectedRevision", revision(f)); }
    private static ObjectNode proposal(Fixture f, String id, String actor, ObjectNode... effects) throws Exception { ObjectNode node = action(f, id, actor).put("reason", "隔离验收作者明确设定的执行边界及事务后果"); ArrayNode rows = node.putArray("effects"); for (ObjectNode effect : effects) rows.add(effect); return node; }
    private static ObjectNode change(String fact, Object expected, Object value) { ObjectNode node = JSON.createObjectNode().put("type", "fact.change").put("factId", fact); node.set("expectedValue", JSON.valueToTree(expected)); node.set("value", JSON.valueToTree(value)); return node; }
    private static ObjectNode observe(String who, String fact) { return JSON.createObjectNode().put("type", "knowledge.learn").put("personId", who).put("factId", fact).put("sourcePersonId", "@world"); }
    private static ObjectNode member(String group, String person) { return JSON.createObjectNode().put("type", "group.member.add").put("groupId", group).put("personId", person); }
    private static JsonNode by(JsonNode rows, String field, String value) { for (JsonNode row : rows) if (row.path(field).asText().equals(value)) return row; throw new AssertionError("缺少 " + field + ":" + value); }
    private static ObjectNode fact(JsonNode view, String id) { return (ObjectNode) by(view.path("facts"), "id", id); }
    private static JsonNode known(JsonNode view, String id) { return by(view.path("knowledge"), "factId", id); }
    private static boolean hasKnown(JsonNode view, String id) { for (JsonNode row : view.path("knowledge")) if (row.path("factId").asText().equals(id)) return true; return false; }
    private static boolean contains(JsonNode rows, String id) { for (JsonNode row : rows) if (row.asText().equals(id)) return true; return false; }
    private static void unchangedReject(Fixture f, Checked action) throws Exception { byte[] bytes = Files.readAllBytes(f.path); JsonNode before = f.world.view("@author").deepCopy(), history = f.world.history().deepCopy(); rejects(action); check(Arrays.equals(bytes, Files.readAllBytes(f.path)), "拒绝保持世界字节"); equal(f.world.view("@author"), before, "拒绝保持全部状态"); equal(f.world.history(), history, "拒绝保持历史"); }
    private static void stable(JsonNode before, JsonNode after) { equal(before.path("places"), after.path("places"), "地理不变"); equal(before.path("routes"), after.path("routes"), "路线不变"); for (JsonNode person : before.path("characters")) { JsonNode current = by(after.path("characters"), "id", person.path("id").asText()); equal(current.path("name"), person.path("name"), "既有人物姓名不变"); equal(current.path("privateProfile"), person.path("privateProfile"), "既有私密资料不改"); } for (JsonNode fact : before.path("facts")) if (fact.path("mode").asText().equals("fixed")) equal(fact(after, fact.path("id").asText()).path("value"), fact.path("value"), "固定事实不变"); }
    private static void privacy(Fixture f, JsonNode view) throws Exception { check(!view.has("facts") && !view.has("laws") && !view.has("entities") && !view.has("characters"), "人物不持作者目录"); for (JsonNode row : view.path("nearby")) check(!row.has("privateProfile"), "别人私密不漏"); check(!JSON.writeValueAsString(view).contains(fact(f.world.view("@author"), "registry-copy-origin").path("value").asText()), "作者幕后资料不漏"); equal(f.world.view("@reader").path("readerKnowledge").size(), 0, "读者认识不自动等于作者或人物认识"); }

    private static final class Counters {
        final Map<Stage, AtomicInteger> stages = new EnumMap<>(Stage.class); final AtomicInteger action = new AtomicInteger();
        Counters() { for (Stage stage : Stage.values()) stages.put(stage, new AtomicInteger()); }
        int total() { return stages.values().stream().mapToInt(AtomicInteger::get).sum(); }
    }
    private static WorldEngine probeHost(String version, Counters calls) { return stageHost(version, calls).register(probeAction(version, calls)); }
    private static WorldEngine stageHost(String version, Counters calls) {
        WorldEngine host = new WorldEngine();
        for (ExperienceComponent base : BuiltinExperienceComponents.all()) host.register(new ExperienceComponent() {
            public String id() { return "skeleton-" + base.stage().key(); } public String version() { return version; } public Stage stage() { return base.stage(); }
            public ObjectNode run(Context context) {
                calls.stages.get(stage()).incrementAndGet(); ObjectNode request = context.request(); request.set("scenario", JSON.createObjectNode());
                if (stage() != Stage.CANDIDATES) return base.run(new Context(request, context.actorView(), context.outputs(), context.receipt()));
                ObjectNode view = context.actorView(); String needed = null;
                for (String factId : List.of("cargo-registration-state", "cargo-seal-status")) if (!known(view, factId).path("source").asText().equals("@world")) { needed = factId; break; }
                ObjectNode output = JSON.createObjectNode().put("status", needed == null ? "satisfied" : "ready"); ArrayNode rows = output.putArray("candidates"); output.putArray("evidence"); output.putArray("gaps");
                if (needed == null) return output.put("reason", "本人已有两项现场认识；该v2声明策略停止，不推导运输结果。");
                ObjectNode candidate = rows.addObject().put("id", "check-" + needed).put("module", "skeleton-observe").put("moduleVersion", version).put("description", "作者声明的v2策略：依次现场清点两项本人已认识的状态").put("priority", 1);
                candidate.putObject("arguments").put("factId", needed); candidate.putArray("evidence").addObject().put("path", "/self/place").put("label", "本人就在货栈").put("equals", "warehouse"); return output;
            }
        }); return host;
    }
    private static ActionModule probeAction(String version, Counters calls) {
        return new ActionModule() {
            public String id() { return "skeleton-observe"; } public String version() { return version; }
            public JsonNode propose(JsonNode actorView, JsonNode input) {
                calls.action.incrementAndGet(); check(!actorView.has("facts") && !actorView.has("laws"), "自定义行为也不能得到作者事实");
                String factId = input.path("factId").asText(); check(List.of("cargo-registration-state", "cargo-seal-status").contains(factId), "自定义策略仅声明两项观察");
                ObjectNode proposal = JSON.createObjectNode(); for (String field : List.of("schemaVersion", "worldId", "requestId", "expectedRevision", "actor")) proposal.set(field, input.get(field).deepCopy());
                proposal.put("reason", "共同宿主明确绑定观察模块v" + version + "，不是默认实现或作者真值推导"); proposal.putArray("requiresKnown").add(factId); proposal.putArray("effects").add(observe(actorView.path("actor").asText(), factId)); return proposal;
            }
        };
    }
    private static ObjectNode probeRequest(Fixture f, String id) throws Exception { ObjectNode request = action(f, id, "keeper").put("intent", "作者明确选择v2策略依次观察登记与封口，不放行不交付"); request.putObject("scenario").putObject("goal").put("id", "keeper-inventory-check").put("kind", "observeLocatedFact").put("factId", "cargo-seal-status").put("locationFactId", "cargo-location").put("label", "按v2声明顺序复核登记与封口"); ArrayNode rows = request.putArray("components"); for (Stage stage : Stage.values()) rows.addObject().put("stage", stage.key()).put("id", "skeleton-" + stage.key()).put("version", "2"); return request; }
    private static void rejects(Checked action) throws Exception { try { action.run(); } catch (IllegalArgumentException | IllegalStateException | java.io.IOException expected) { return; } throw new AssertionError("非法操作未拒绝"); }
    private static void run(String name, Checked action) { ObjectNode result = JSON.createObjectNode().put("name", name); try { action.run(); result.put("passed", true); } catch (Exception | AssertionError failure) { result.put("passed", false).put("error", failure.getClass().getSimpleName() + ": " + failure.getMessage()); } RESULTS.add(result); }
    private static void equal(Object actual, Object expected, String why) { boolean same = actual instanceof JsonNode a && expected instanceof JsonNode b ? Protocol.canonical(a).equals(Protocol.canonical(b)) : java.util.Objects.equals(actual, expected); check(same, why + "；预期=" + expected + "；实际=" + actual); }
    private static void check(boolean condition, String why) { if (!condition) throw new AssertionError(why); }
    @FunctionalInterface private interface Checked { void run() throws Exception; }
}
