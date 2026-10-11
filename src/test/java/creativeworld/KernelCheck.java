package creativeworld;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.DecimalNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** 独立临时世界的黑盒核对；不评价人物心理或小说阅读质量。 */
public final class KernelCheck {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<ObjectNode> RESULTS = new ArrayList<>();
    private static Path testRoot;
    private static int fixtureNumber;

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("--hold-lock")) {
            if (args.length != 4) throw new IllegalArgumentException("内部持锁模式需要lock、ready、release文件");
            holdLock(Path.of(args[1]), Path.of(args[2]), Path.of(args[3])); return;
        }
        testRoot = Files.createTempDirectory("creative-world-kernel-check-");

        run("作者、人物、读者资料分开，初始事实有明确身份", () -> {
            Fixture f = fresh();
            equal(f.store.inspect().path("revision").asInt(), 0, "初始版本");
            JsonNode author = f.store.view("@author");
            equal(fact(author, "cargo-place").path("value").asText(), "warehouse", "作者知道货箱位置");
            equal(fact(author, "town-name").path("value").asText(), "河港镇", "固定地名");
            JsonNode apprentice = f.store.view("apprentice");
            check(!apprentice.has("facts") && !apprentice.has("characters"), "人物资料不得带全量事实和人物");
            check(!apprentice.toString().contains("SECRET-LOCK-CODE"), "隐蔽事实泄漏");
            equal(apprentice.path("self").path("name").asText(), "阿若", "人物身份");
            equal(apprentice.path("self").path("privateProfile").path("note").asText(), "自己的顾虑", "本人可以看自己的私有资料");
            JsonNode boatman = byId(apprentice.path("nearby"), "boatman");
            check(!boatman.has("privateProfile") && !apprentice.toString().contains("船工私事"), "不能看同地他人的私有资料");
            check(!containsId(apprentice.path("nearby"), "smith"), "不在同地的人不能列为附近人物");
            check(!hasKnowledge(apprentice, "cargo-place"), "尚未获知货箱位置");
            JsonNode reader = f.store.view("@reader");
            check(reader.path("readerKnowledge").isEmpty(), "读者初始没有被揭示的事实");
            check(!reader.has("facts") && !reader.has("knowledge") && !reader.has("self"), "读者不能得到作者或人物全量资料");
        });

        run("固定事实和现有人物姓名不能被后续事件改写", () -> {
            Fixture f = fresh();
            unchangedOnReject(f, proposal(f, "fixed-change", "@author", change("town-name", "河港镇", "另一个镇")));
            ObjectNode rename = effect("person.rename"); rename.put("personId", "apprentice"); rename.put("name", "新名字");
            unchangedOnReject(f, proposal(f, "rename", "@author", rename));
            equal(byId(f.store.view("@author").path("characters"), "apprentice").path("name").asText(), "阿若", "姓名仍是原身份");
        });

        run("可变事实只向前追加，旧事件和人物旧认识保留", () -> {
            Fixture f = fresh(); JsonNode previousKnowledge = f.store.view("boatman").deepCopy();
            ObjectNode p = proposal(f, "move-cargo", "@author", change("cargo-place", "warehouse", "harbor"));
            f.store.apply(p);
            equal(f.store.inspect().path("revision").asInt(), 1, "追加后的版本");
            equal(fact(f.store.view("@author"), "cargo-place").path("value").asText(), "harbor", "当前真实位置改变");
            equal(knowledge(f.store.view("boatman"), "cargo-place").path("value").asText(), "warehouse", "船工原有认识没有被真相自动改写");
            ObjectNode currentKnowledge = (ObjectNode) f.store.view("boatman").deepCopy(); currentKnowledge.put("revision", 0);
            equal(previousKnowledge, currentKnowledge, "先前人物资料快照不被共享修改");
            JsonNode event = f.store.history().path("events").get(0);
            equal(event.path("revision").asInt(), 1, "事件版本");
            equal(event.path("proposal"), JSON.readTree(p.toString()), "事件保留完整行动依据与变化");
            equal(event.path("proposal").path("reason").asText(), "核对当前情况后执行", "事件不是没有缘由的占位记录");
        });

        run("交流复制来源人的已知快照，现场观察才更新真实值", () -> {
            Fixture f = fresh();
            f.store.apply(proposal(f, "cargo-moved", "@author", change("cargo-place", "warehouse", "harbor")));
            f.store.apply(proposal(f, "heard-old-location", "apprentice", learn("apprentice", "cargo-place", "boatman")));
            JsonNode learned = knowledge(f.store.view("apprentice"), "cargo-place").deepCopy();
            equal(learned.path("value").asText(), "warehouse", "不能从交流偷取当前世界真相");
            equal(learned.path("source").asText(), "boatman", "听闻来源");
            f.store.apply(proposal(f, "observed-location", "apprentice", learn("apprentice", "cargo-place", "@world")));
            equal(knowledge(f.store.view("apprentice"), "cargo-place").path("value").asText(), "harbor", "可观察地点提供当时真实值");
            equal(learned.path("value").asText(), "warehouse", "旧听闻快照仍是旧值");
            equal(f.store.history().path("events").get(1).path("proposal").path("effects").get(0).path("sourcePersonId").asText(), "boatman", "历史仍保留听闻来源");
        });

        run("不能隔空学习、凭空获知隐情或冒用别人的行动身份", () -> {
            Fixture f = fresh();
            unchangedOnReject(f, proposal(f, "remote-learn", "apprentice", learn("apprentice", "town-name", "smith")));
            unchangedOnReject(f, proposal(f, "hidden-observe", "apprentice", learn("apprentice", "lock-code", "@world")));
            unchangedOnReject(f, proposal(f, "teach-for-other", "apprentice", learn("boatman", "town-name", "apprentice")));
            ObjectNode unknown = proposal(f, "requires-unknown", "apprentice", belief("apprentice", "想先找人问问"));
            unknown.putArray("requiresKnown").add("cargo-place"); unchangedOnReject(f, unknown);
            unchangedOnReject(f, proposal(f, "other-move", "apprentice", move("boatman", "harbor", "warehouse")));
        });

        run("同一请求重放不重复落账，改动请求与过期版本被拒绝", () -> {
            Fixture f = fresh(); ObjectNode p = proposal(f, "same-request", "apprentice", belief("apprentice", "先核对清单"));
            JsonNode receipt = f.store.apply(p); byte[] bytes = Files.readAllBytes(f.path);
            equal(f.store.apply(p.deepCopy()), receipt, "同一请求返回原回执");
            check(Arrays.equals(bytes, Files.readAllBytes(f.path)), "重放不改保存文件");
            ObjectNode reordered = JSON.createObjectNode();
            List<String> keys = new ArrayList<>(); p.fieldNames().forEachRemaining(keys::add);
            java.util.Collections.reverse(keys); for (String key : keys) reordered.set(key, p.get(key));
            equal(f.store.apply(reordered), receipt, "对象字段顺序不构成新请求");
            ObjectNode conflict = p.deepCopy(); conflict.put("reason", "另一种原因"); unchangedOnReject(f, conflict);
            ObjectNode stale = proposal(f, "stale-request", "apprentice", belief("apprentice", "另一个想法"));
            stale.put("expectedRevision", 0); unchangedOnReject(f, stale);
            equal(f.store.history().path("events").size(), 1, "只提交一次事件");
        });

        run("多项变化全部有效才落账，后项失败不会留下前项", () -> {
            Fixture f = fresh();
            ObjectNode p = proposal(f, "partial-rejected", "@author", change("cargo-place", "warehouse", "harbor"), change("town-name", "河港镇", "错误改名"));
            unchangedOnReject(f, p);
            equal(fact(f.store.view("@author"), "cargo-place").path("value").asText(), "warehouse", "先前可变项也没有偷偷提交");
            unchangedOnReject(f, proposal(f, "wrong-expected", "@author", change("cargo-place", "wrong-location", "harbor")));
            ObjectNode wrongWorld = proposal(f, "wrong-world", "apprentice", belief("apprentice", "一个猜测"));
            wrongWorld.put("worldId", "other-world"); unchangedOnReject(f, wrongWorld);
            ObjectNode missingReason = proposal(f, "empty-reason", "apprentice", belief("apprentice", "一个猜测"));
            missingReason.put("reason", ""); unchangedOnReject(f, missingReason);
        });

        run("重复身份和重复事实不能形成第二套权威资料", () -> {
            Fixture f = fresh();
            ObjectNode duplicateFact = define("new-cargo-name", "cargo", "location", "harbor", "state", "apprentice");
            unchangedOnReject(f, proposal(f, "duplicate-fact", "@author", factDefine(duplicateFact)));
            unchangedOnReject(f, proposal(f, "duplicate-place", "@author", placeAdd("harbor", "另一个码头")));
            unchangedOnReject(f, proposal(f, "duplicate-person", "@author", personAdd("apprentice", "另一个人", "harbor")));
            unchangedOnReject(f, proposal(f, "actor-add", "apprentice", placeAdd("new-place", "新地点")));
        });

        run("可以增量建地点、人物与事实，保存后继续原世界", () -> {
            Fixture f = fresh();
            f.store.apply(proposal(f, "extend-world", "@author", placeAdd("market", "集市"), personAdd("merchant", "许掌柜", "market"),
                factDefine(define("merchant-role", "merchant", "occupation", "商人", "fixed", "merchant"))));
            WorldStore restored = new WorldStore(f.path);
            equal(restored.inspect().path("revision").asInt(), 1, "增量资料恢复版本");
            equal(byId(restored.view("@author").path("places"), "market").path("name").asText(), "集市", "新增地点恢复");
            equal(restored.view("merchant").path("self").path("name").asText(), "许掌柜", "新增人物恢复");
            equal(knowledge(restored.view("merchant"), "merchant-role").path("value").asText(), "商人", "新增事实和人物认识恢复");
            restored.apply(proposal(restored, "merchant-next-step", "merchant", belief("merchant", "先准备明天的货")));
            equal(restored.inspect().path("revision").asInt(), 2, "恢复后继续向前");
        });

        run("人物信念与关系不是世界真相，变化遵守身份和旧值", () -> {
            Fixture f = fresh();
            f.store.apply(proposal(f, "own-belief-and-relation", "apprentice", belief("apprentice", "船工可能记错了"), relation("apprentice", "boatman", "认识", "愿意请教")));
            JsonNode view = f.store.view("apprentice");
            equal(view.path("beliefs").get(0).path("text").asText(), "船工可能记错了", "信念独立保存");
            equal(view.path("relationships").get(0).path("value").asText(), "愿意请教", "关系状态改变");
            check(!f.store.view("@reader").toString().contains("船工可能记错了"), "私人信念不自动给读者");
            equal(fact(f.store.view("@author"), "cargo-place").path("value").asText(), "warehouse", "猜测不改世界事实");
            unchangedOnReject(f, proposal(f, "other-belief", "apprentice", belief("boatman", "被别人安排的想法")));
            unchangedOnReject(f, proposal(f, "other-relation", "apprentice", relation("boatman", "apprentice", "认识", "变化")));
            unchangedOnReject(f, proposal(f, "relation-conflict", "apprentice", relation("apprentice", "boatman", "错误旧值", "另一种关系")));
        });

        run("读者只获得明确揭示的快照，不自动追随隐藏世界变化", () -> {
            Fixture f = fresh();
            unchangedOnReject(f, proposal(f, "actor-reveal", "apprentice", reveal("cargo-place")));
            f.store.apply(proposal(f, "author-reveal", "@author", reveal("cargo-place")));
            JsonNode earlier = f.store.view("@reader").deepCopy();
            equal(knowledgeFrom(earlier.path("readerKnowledge"), "cargo-place").path("value").asText(), "warehouse", "明确揭示的值");
            f.store.apply(proposal(f, "hidden-later-change", "@author", change("cargo-place", "warehouse", "harbor")));
            equal(knowledgeFrom(f.store.view("@reader").path("readerKnowledge"), "cargo-place").path("value").asText(), "warehouse", "读者尚未看到后来的变更");
            check(!f.store.view("@reader").toString().contains("SECRET-LOCK-CODE"), "仍不暴露其他秘密");
        });

        run("重新加载以事件重演恢复全量资料和各人的认识", () -> {
            Fixture f = fresh();
            f.store.apply(proposal(f, "step-one", "apprentice", learn("apprentice", "cargo-place", "boatman"), belief("apprentice", "需要核对货箱")));
            f.store.apply(proposal(f, "step-two", "@author", change("cargo-place", "warehouse", "harbor"), reveal("cargo-place")));
            JsonNode author = f.store.view("@author").deepCopy(); JsonNode actor = f.store.view("apprentice").deepCopy();
            JsonNode reader = f.store.view("@reader").deepCopy(); JsonNode history = f.store.history().deepCopy();
            WorldStore reloaded = new WorldStore(f.path);
            equal(reloaded.view("@author"), author, "全量重演一致");
            equal(reloaded.view("apprentice"), actor, "人物认识重演一致");
            equal(reloaded.view("@reader"), reader, "读者认识重演一致");
            equal(reloaded.history(), history, "历史重演一致");
            JsonNode saved = JSON.readTree(f.path.toFile());
            equal(saved.path("schemaVersion").asInt(), 1, "保存协议版本");
            equal(saved.path("seed").path("facts").get(1).path("value").asText(), "warehouse", "起点资料不被后续事实覆盖");
            equal(saved.path("events").size(), 2, "保存完整增量事件");
        });

        run("未知保存版本和未知历史效果不能被静默跳过", () -> {
            Fixture future = fresh(); ObjectNode saved = (ObjectNode) JSON.readTree(future.path.toFile());
            saved.put("schemaVersion", 999); JSON.writeValue(future.path.toFile(), saved);
            byte[] bytes = Files.readAllBytes(future.path);
            rejects(() -> new WorldStore(future.path).inspect());
            check(Arrays.equals(bytes, Files.readAllBytes(future.path)), "拒绝未知版本时不能自作主张重写保存文件");
            Fixture unknown = fresh();
            unknown.store.apply(proposal(unknown, "known-history", "apprentice", belief("apprentice", "一个猜测")));
            ObjectNode corrupted = (ObjectNode) JSON.readTree(unknown.path.toFile());
            ((ObjectNode) corrupted.path("events").get(0).path("proposal").path("effects").get(0)).put("type", "world.rewrite");
            JSON.writeValue(unknown.path.toFile(), corrupted); byte[] corruptedBytes = Files.readAllBytes(unknown.path);
            rejects(() -> new WorldStore(unknown.path).inspect());
            check(Arrays.equals(corruptedBytes, Files.readAllBytes(unknown.path)), "非法历史恢复不能改写原文件");
        });

        run("新动作模块只接收人物资料，并由同一校验器提交", () -> {
            Fixture f = fresh(); final List<JsonNode> seen = new ArrayList<>();
            f.store.register(new ActionModule() {
                public String id() { return "notice"; }
                public JsonNode propose(JsonNode actorView, JsonNode input) {
                    seen.add(actorView.deepCopy());
                    return moduleProposal(actorView, input, belief(actorView.path("actor").asText(), "先整理手头记录"));
                }
            });
            ObjectNode moduleRequest = moduleInput(f.store, "module-notice", "apprentice");
            JsonNode moduleReceipt = f.store.step("notice", moduleRequest);
            equal(seen.size(), 1, "模块被调用一次");
            check(!seen.get(0).has("facts") && !seen.get(0).toString().contains("SECRET-LOCK-CODE"), "模块未获得世界隐藏资料");
            equal(f.store.inspect().path("revision").asInt(), 1, "扩展模块通过统一提交产生事件");
            byte[] once = Files.readAllBytes(f.path);
            equal(f.store.step("notice", moduleRequest.deepCopy()), moduleReceipt, "同一完整模块输入返回原回执");
            equal(seen.size(), 1, "成功重放不能再次调用模块");
            check(Arrays.equals(once, Files.readAllBytes(f.path)), "模块重放不重复落账");
            ObjectNode changedInput = moduleRequest.deepCopy(); changedInput.put("hint", "改变了完整输入");
            rejects(() -> f.store.step("notice", changedInput));
            equal(seen.size(), 1, "同请求不同输入在调用模块前被拒绝");
            unchangedOnReject(f, f.store.history().path("events").get(0).path("proposal").deepCopy());
            ObjectNode longInput = moduleInput(f.store, "module-number", "apprentice"); longInput.put("expectedRevision", 1L);
            JsonNode longReceipt = f.store.step("notice", longInput);
            ObjectNode ordinaryNumber = longInput.deepCopy(); ordinaryNumber.put("expectedRevision", 1);
            equal(f.store.step("notice", ordinaryNumber), longReceipt, "模块整数实现类型变化仍是同一个输入");
            equal(seen.size(), 2, "数值规范化重放没有再调用模块");
            f.store.register(new ActionModule() {
                public String id() { return "bad-notice"; }
                public JsonNode propose(JsonNode actorView, JsonNode input) {
                    return moduleProposal(actorView, input, belief("apprentice", "这项也不应留下"), move("boatman", "harbor", "warehouse"));
                }
            });
            byte[] before = Files.readAllBytes(f.path); JsonNode state = f.store.view("@author").deepCopy();
            rejects(() -> f.store.step("bad-notice", moduleRequest.deepCopy()));
            check(Arrays.equals(before, Files.readAllBytes(f.path)), "同请求换模块不能重复提交");
            rejects(() -> f.store.step("bad-notice", moduleInput(f.store, "module-invalid", "apprentice")));
            check(Arrays.equals(before, Files.readAllBytes(f.path)), "无效扩展动作不能绕过文件原子性");
            equal(f.store.view("@author"), state, "扩展动作失败时内存状态也不变");
            f.store.register(new ActionModule() {
                public String id() { return "upgrade-author"; }
                public JsonNode propose(JsonNode actorView, JsonNode input) {
                    ObjectNode p = moduleProposal(actorView, input, placeAdd("illegal-place", "不应出现的地点"));
                    p.put("actor", "@author"); return p;
                }
            });
            rejects(() -> f.store.step("upgrade-author", moduleInput(f.store, "module-escalated", "apprentice")));
            check(Arrays.equals(before, Files.readAllBytes(f.path)), "模块不能将人物身份升级为作者");
            equal(f.store.view("@author"), state, "模块改身份被拒绝后世界不变");
        });

        run("模块不能覆盖已有实现，重新注册扩展可继续保存的世界", () -> {
            Fixture f = fresh(); ActionModule custom = simpleModule("remember"); f.store.register(custom);
            rejects(() -> f.store.register(simpleModule("remember")));
            rejects(() -> f.store.register(simpleModule("ask")));
            f.store.step("remember", moduleInput(f.store, "extension-first", "apprentice"));
            WorldStore restored = new WorldStore(f.path); restored.register(simpleModule("remember"));
            restored.step("remember", moduleInput(restored, "extension-second", "apprentice"));
            equal(restored.inspect().path("revision").asInt(), 2, "扩展既有状态向前继续");
            equal(restored.view("apprentice").path("beliefs").size(), 2, "前一次扩展产生的资料没有丢失");
        });

        run("同一世界只向前推进，不能重新初始化或重写历史", () -> {
            Fixture f = fresh(); f.store.apply(proposal(f, "existing-event", "apprentice", belief("apprentice", "已经发生的想法")));
            byte[] before = Files.readAllBytes(f.path); JsonNode state = f.store.view("@author").deepCopy();
            rejects(() -> f.store.initialize(seed()));
            check(Arrays.equals(before, Files.readAllBytes(f.path)), "重新初始化不得重置已有世界");
            equal(f.store.view("@author"), state, "重新初始化被拒绝后状态保留");
            unchangedOnReject(f, proposal(f, "rewrite-event", "@author", effect("history.rewrite")));
            ObjectNode futureProposal = proposal(f, "future-protocol", "apprentice", belief("apprentice", "一个猜测"));
            futureProposal.put("schemaVersion", 2); unchangedOnReject(f, futureProposal);
        });

        run("显式有向路线禁止跨点跳跃，前进与返回分别需要连接", () -> {
            ObjectNode connectedSeed = seed();
            ((ArrayNode) connectedSeed.path("places")).add(place("forge", "工坊"));
            connectedSeed.putArray("routes").add(route("harbor", "warehouse")).add(route("warehouse", "forge"));
            Fixture f = fresh(connectedSeed);
            unchangedOnReject(f, proposal(f, "skip-warehouse", "apprentice", move("apprentice", "harbor", "forge")));
            f.store.apply(proposal(f, "go-warehouse", "apprentice", move("apprentice", "harbor", "warehouse")));
            equal(f.store.view("apprentice").path("self").path("place").asText(), "warehouse", "第一段路线可走");
            f.store.apply(proposal(f, "go-forge", "apprentice", move("apprentice", "warehouse", "forge")));
            equal(f.store.view("apprentice").path("self").path("place").asText(), "forge", "第二段路线可走");
            unchangedOnReject(f, proposal(f, "return-without-edge", "apprentice", move("apprentice", "forge", "warehouse")));
            f.store.apply(proposal(f, "add-return-edge", "@author", routeAdd("forge", "warehouse")));
            f.store.apply(proposal(f, "return-on-new-edge", "apprentice", move("apprentice", "forge", "warehouse")));
            equal(f.store.view("apprentice").path("self").path("place").asText(), "warehouse", "作者追加反向路线后可返回");
            equal(f.store.inspect().path("counts").path("routes").asInt(), 3, "有向路线独立计数");
            ObjectNode emptyRoutes = seed(); emptyRoutes.putArray("routes");
            Fixture disconnected = fresh(emptyRoutes);
            unchangedOnReject(disconnected, proposal(disconnected, "empty-routes-jump", "apprentice", move("apprentice", "harbor", "warehouse")));
        });

        run("首次追加路线开启限制，恢复后保留路线和此前未定义拓扑的历史", () -> {
            Fixture f = fresh();
            f.store.apply(proposal(f, "legacy-move", "apprentice", move("apprentice", "harbor", "warehouse")));
            JsonNode originalEvent = f.store.history().path("events").get(0).deepCopy();
            f.store.apply(proposal(f, "first-route", "@author", routeAdd("warehouse", "harbor")));
            WorldStore reloaded = new WorldStore(f.path);
            equal(reloaded.history().path("events").get(0), originalEvent, "首次配置路线不重写之前的历史");
            equal(reloaded.inspect().path("counts").path("routes").asInt(), 1, "路线追加状态恢复");
            equal(reloaded.view("@author").path("routes").get(0), route("warehouse", "harbor"), "恢复后的路线端点");
            reloaded.apply(proposal(reloaded, "follow-first-route", "apprentice", move("apprentice", "warehouse", "harbor")));
            Fixture restored = new Fixture(f.path, reloaded);
            unchangedOnReject(restored, proposal(reloaded, "now-guarded", "apprentice", move("apprentice", "harbor", "warehouse")));
            unchangedOnReject(restored, proposal(reloaded, "duplicate-route", "@author", routeAdd("warehouse", "harbor")));
            unchangedOnReject(restored, proposal(reloaded, "unknown-route-end", "@author", routeAdd("harbor", "missing-place")));
            unchangedOnReject(restored, proposal(reloaded, "self-route", "@author", routeAdd("harbor", "harbor")));
            ObjectNode malformed = routeAdd("harbor", "warehouse"); malformed.put("distance", 2);
            unchangedOnReject(restored, proposal(reloaded, "unsupported-route-field", "@author", malformed));
            unchangedOnReject(restored, proposal(reloaded, "actor-add-route", "apprentice", routeAdd("harbor", "warehouse")));
            equal(reloaded.history().path("events").get(0), originalEvent, "所有失败检查后原历史仍保留");
        });

        run("直接提案在入口冻结，外部文件锁等待期间改写调用方对象不改变提交", () -> {
            Fixture f = fresh();
            ObjectNode incoming = proposal(f, "apply-entry-freeze", "@author", change("cargo-place", "warehouse", "harbor"));
            ObjectNode original = incoming.deepCopy();
            Path ready = testRoot.resolve("external-lock-ready"), release = testRoot.resolve("external-lock-release");
            Path log = testRoot.resolve("external-lock.log");
            String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
            Process holder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                "-cp", System.getProperty("java.class.path"), KernelCheck.class.getName(), "--hold-lock",
                f.path.resolveSibling(f.path.getFileName() + ".lock").toString(), ready.toString(), release.toString())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
            AtomicReference<JsonNode> receipt = new AtomicReference<>();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread worker = new Thread(() -> {
                try { receipt.set(f.store.apply(incoming)); }
                catch (Throwable error) { failure.set(error); }
            }, "kernel-apply-entry-freeze");
            worker.setDaemon(true);
            try {
                await(() -> {
                    check(holder.isAlive(), "外部持锁进程提前退出：" + Files.readString(log));
                    return Files.exists(ready);
                }, "外部进程没有在期限内持有文件锁");
                worker.start();
                await(() -> {
                    check(worker.isAlive(), "apply未进入外部文件锁等待：" + failure.get());
                    return waitingForFileLock(worker);
                }, "没有观察到apply在FileChannel.lock中等待，不能用猜测的延迟改写参数");
                incoming.put("reason", "等待期间被调用方改写的原因");
                ((ObjectNode) incoming.path("effects").get(0)).put("value", "warehouse");
                Files.writeString(release, "release");
                check(holder.waitFor(3, TimeUnit.SECONDS), "外部持锁进程没有在释放后退出");
                equal(holder.exitValue(), 0, "外部持锁进程正常释放锁");
                worker.join(3000); check(!worker.isAlive(), "apply释放锁后仍未结束");
                if (failure.get() != null) throw new AssertionError("apply等待锁后失败", failure.get());
                WorldStore reopened = new WorldStore(f.path);
                equal(reopened.inspect().path("revision").asInt(), 1, "只提交一次入口提案");
                equal(fact(reopened.view("@author"), "cargo-place").path("value").asText(), "harbor", "提交入口时的原值");
                equal(reopened.history().path("events").get(0).path("proposal"), JSON.readTree(original.toString()), "重开历史仍保留完整原输入");
                byte[] saved = Files.readAllBytes(f.path);
                equal(reopened.apply(original), receipt.get(), "原输入重放返回原回执");
                check(Arrays.equals(saved, Files.readAllBytes(f.path)), "原输入重放没有再次写入历史");
                equal(incoming.path("reason").asText(), "等待期间被调用方改写的原因", "提交器没有覆盖调用方持有的对象");
            } finally {
                try { Files.writeString(release, "release"); }
                finally {
                    if (!holder.waitFor(3, TimeUnit.SECONDS)) {
                        holder.destroyForcibly(); check(holder.waitFor(3, TimeUnit.SECONDS), "外部持锁进程无法有界终止");
                    }
                    worker.join(3000);
                    if (worker.isAlive()) { worker.interrupt(); worker.join(1000); }
                    check(!worker.isAlive(), "apply测试线程无法有界终止");
                }
            }
        });

        run("精确十进制事实保存重开和事件重放均不缩水，原提案可返回原回执", () -> {
            BigDecimal oldValue = new BigDecimal("0.12345678901234567890123456789");
            BigDecimal newValue = new BigDecimal("0.98765432109876543210987654321");
            ObjectNode initial = seed();
            ObjectNode precise = define("precise-stock", "iron-stock", "quantity", "unused", "state", "apprentice");
            precise.set("value", DecimalNode.valueOf(oldValue)); ((ArrayNode) initial.path("facts")).add(precise);
            Fixture f = fresh(initial); WorldStore reopened = new WorldStore(f.path);
            equal(reopened.inspect().path("counts").path("facts").asInt(), 4, "重开后精确事实仍在");
            decimal(fact(reopened.view("@author"), "precise-stock").path("value"), oldValue, "初始真实值不缩水");
            decimal(knowledge(reopened.view("apprentice"), "precise-stock").path("value"), oldValue, "本人已知初始值不缩水");
            ObjectNode change = effect("fact.change"); change.put("factId", "precise-stock");
            change.set("expectedValue", DecimalNode.valueOf(oldValue)); change.set("value", DecimalNode.valueOf(newValue));
            ObjectNode p = proposal(reopened, "precise-change", "@author", change); JsonNode receipt = reopened.apply(p);
            WorldStore replayed = new WorldStore(f.path);
            equal(replayed.inspect().path("revision").asInt(), 1, "精确值事件可恢复");
            decimal(fact(replayed.view("@author"), "precise-stock").path("value"), newValue, "重放后的新真实值不缩水");
            decimal(knowledge(replayed.view("apprentice"), "precise-stock").path("value"), oldValue, "旧认识既不缩水也不自动替换");
            JsonNode recordedChange = replayed.history().path("events").get(0).path("proposal").path("effects").get(0);
            decimal(recordedChange.path("expectedValue"), oldValue, "历史保留精确旧值");
            decimal(recordedChange.path("value"), newValue, "历史保留精确新值");
            byte[] saved = Files.readAllBytes(f.path);
            equal(replayed.apply(p.deepCopy()), receipt, "精确输入重放返回原回执");
            check(Arrays.equals(saved, Files.readAllBytes(f.path)), "精确输入重放没有重复落账");
            equal(new WorldStore(f.path).history().path("events").size(), 1, "再重开仍只有一个精确事件");
        });

        ObjectNode report = JSON.createObjectNode(); report.put("checks", RESULTS.size());
        long passed = RESULTS.stream().filter(r -> r.path("passed").asBoolean()).count();
        report.put("passed", passed); report.put("failed", RESULTS.size() - passed);
        report.set("results", JSON.valueToTree(RESULTS)); report.put("actualModelCalled", false);
        report.put("formalServiceUsed", false); report.put("proseQualityEvaluated", false);
        report.put("evidenceLimit", "仅核对独立世界内核的状态、信息边界和持久化；不证明人物心理、小说质量或模型创作能力。");
        report.put("isolatedTestDirectory", testRoot.toString());
        if (args.length > 0) {
            Path output = Path.of(args[0]).toAbsolutePath(); Files.createDirectories(output.getParent());
            JSON.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);
        }
        System.out.println(JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report));
        if (passed != RESULTS.size()) System.exit(1);
    }

    @SuppressWarnings("try")
    private static void holdLock(Path lock, Path ready, Path release) throws Exception {
        try (FileChannel channel = FileChannel.open(lock, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignored = channel.lock()) {
            Files.writeString(ready, "locked");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!Files.exists(release)) {
                if (System.nanoTime() >= deadline) throw new IOException("内部持锁超过10秒，自动释放");
                Thread.sleep(10);
            }
        }
    }
    private static boolean waitingForFileLock(Thread worker) {
        boolean store = false, channel = false;
        for (StackTraceElement frame : worker.getStackTrace()) {
            store |= frame.getClassName().equals(WorldStore.class.getName()) && frame.getMethodName().equals("locked");
            channel |= frame.getClassName().equals("sun.nio.ch.FileChannelImpl") && frame.getMethodName().equals("lock");
        }
        return store && channel;
    }
    private static void await(CheckedCondition condition, String why) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (!condition.get()) {
            if (System.nanoTime() >= deadline) throw new AssertionError(why);
            Thread.sleep(10);
        }
    }
    private static void decimal(JsonNode actual, BigDecimal expected, String why) {
        check(actual.isNumber() && actual.decimalValue().compareTo(expected) == 0, why + "；预期=" + expected + "；实际=" + actual);
    }

    private record Fixture(Path path, WorldStore store) {}
    private static Fixture fresh() throws Exception { return fresh(seed()); }
    private static Fixture fresh(ObjectNode initial) throws Exception {
        Path path = testRoot.resolve("world-" + ++fixtureNumber + ".json");
        WorldStore store = new WorldStore(path); store.initialize(initial); return new Fixture(path, store);
    }
    private static ObjectNode seed() {
        ObjectNode root = JSON.createObjectNode(); root.put("schemaVersion", 1); root.put("worldId", "kernel-town"); root.put("title", "河港镇");
        root.putArray("places").add(place("harbor", "河港")).add(place("warehouse", "仓库"));
        ArrayNode people = root.putArray("characters");
        people.add(person("apprentice", "阿若", "harbor", "自己的顾虑"));
        people.add(person("boatman", "老周", "harbor", "船工私事"));
        people.add(person("smith", "何师傅", "warehouse", "铁匠私事"));
        ArrayNode facts = root.putArray("facts");
        facts.add(define("town-name", "town", "name", "河港镇", "fixed", "apprentice", "boatman", "smith"));
        ObjectNode cargo = define("cargo-place", "cargo", "location", "warehouse", "state", "boatman"); cargo.putArray("observableAt").add("harbor"); facts.add(cargo);
        facts.add(define("lock-code", "warehouse", "lockCode", "SECRET-LOCK-CODE", "fixed"));
        ObjectNode relation = JSON.createObjectNode(); relation.put("from", "apprentice"); relation.put("to", "boatman"); relation.put("value", "认识");
        root.putArray("relationships").add(relation); return root;
    }
    private static ObjectNode place(String id, String name) { ObjectNode p = JSON.createObjectNode(); p.put("id", id); p.put("name", name); return p; }
    private static ObjectNode person(String id, String name, String place, String privateNote) {
        ObjectNode p = JSON.createObjectNode(); p.put("id", id); p.put("name", name); p.put("place", place);
        p.putObject("publicProfile").put("description", "这里的一位居民"); p.putObject("privateProfile").put("note", privateNote); return p;
    }
    private static ObjectNode define(String id, String subject, String predicate, String value, String mode, String... knownBy) {
        ObjectNode f = JSON.createObjectNode(); f.put("id", id); f.put("subject", subject); f.put("predicate", predicate); f.put("value", value); f.put("mode", mode);
        ArrayNode known = f.putArray("knownBy"); for (String who : knownBy) known.add(who); return f;
    }
    private static ObjectNode effect(String type) { ObjectNode e = JSON.createObjectNode(); e.put("type", type); return e; }
    private static ObjectNode route(String from, String to) { ObjectNode r = JSON.createObjectNode(); r.put("from", from); r.put("to", to); return r; }
    private static ObjectNode routeAdd(String from, String to) { ObjectNode e = effect("route.add"); e.put("from", from); e.put("to", to); return e; }
    private static ObjectNode change(String id, String expected, String value) { ObjectNode e = effect("fact.change"); e.put("factId", id); e.put("expectedValue", expected); e.put("value", value); return e; }
    private static ObjectNode learn(String who, String fact, String source) { ObjectNode e = effect("knowledge.learn"); e.put("personId", who); e.put("factId", fact); e.put("sourcePersonId", source); return e; }
    private static ObjectNode belief(String who, String text) { ObjectNode e = effect("belief.add"); e.put("personId", who); e.put("text", text); return e; }
    private static ObjectNode move(String who, String from, String to) { ObjectNode e = effect("person.move"); e.put("personId", who); e.put("expectedPlace", from); e.put("to", to); return e; }
    private static ObjectNode relation(String from, String to, String old, String value) { ObjectNode e = effect("relationship.change"); e.put("from", from); e.put("to", to); e.put("expectedValue", old); e.put("value", value); return e; }
    private static ObjectNode reveal(String id) { ObjectNode e = effect("reader.reveal"); e.put("factId", id); return e; }
    private static ObjectNode placeAdd(String id, String name) { ObjectNode e = effect("place.add"); e.set("place", place(id, name)); return e; }
    private static ObjectNode personAdd(String id, String name, String at) { ObjectNode e = effect("person.add"); e.set("person", person(id, name, at, "新居民私事")); return e; }
    private static ObjectNode factDefine(ObjectNode fact) { ObjectNode e = effect("fact.define"); e.set("fact", fact); return e; }
    private static ObjectNode proposal(Fixture f, String requestId, String actor, ObjectNode... effects) throws Exception { return proposal(f.store, requestId, actor, effects); }
    private static ObjectNode proposal(WorldStore store, String requestId, String actor, ObjectNode... effects) throws Exception {
        ObjectNode p = JSON.createObjectNode(); p.put("schemaVersion", 1); p.put("worldId", "kernel-town"); p.put("requestId", requestId);
        p.put("expectedRevision", store.inspect().path("revision").asLong()); p.put("actor", actor); p.put("reason", "核对当前情况后执行");
        ArrayNode list = p.putArray("effects"); for (ObjectNode e : effects) list.add(e); return p;
    }
    private static ObjectNode moduleInput(WorldStore store, String requestId, String actor) throws Exception {
        ObjectNode input = JSON.createObjectNode(); input.put("schemaVersion", 1); input.put("worldId", "kernel-town");
        input.put("requestId", requestId); input.put("expectedRevision", store.inspect().path("revision").asInt()); input.put("actor", actor); return input;
    }
    private static ObjectNode moduleProposal(JsonNode view, JsonNode input, ObjectNode... effects) {
        ObjectNode p = JSON.createObjectNode(); p.put("schemaVersion", 1); p.set("worldId", view.path("worldId")); p.set("requestId", input.path("requestId"));
        p.set("expectedRevision", view.path("revision")); p.set("actor", view.path("actor")); p.put("reason", "根据当前可见资料提出下一步");
        ArrayNode list = p.putArray("effects"); for (ObjectNode e : effects) list.add(e); return p;
    }
    private static ActionModule simpleModule(String id) {
        return new ActionModule() {
            public String id() { return id; }
            public JsonNode propose(JsonNode actorView, JsonNode input) { return moduleProposal(actorView, input, belief(actorView.path("actor").asText(), "记录：" + input.path("requestId").asText())); }
        };
    }
    private static JsonNode fact(JsonNode author, String id) { return byId(author.path("facts"), id); }
    private static JsonNode byId(JsonNode array, String id) { for (JsonNode item : array) if (item.path("id").asText().equals(id)) return item; throw new AssertionError("缺少资料：" + id); }
    private static boolean containsId(JsonNode array, String id) { for (JsonNode item : array) if (item.path("id").asText().equals(id)) return true; return false; }
    private static JsonNode knowledge(JsonNode view, String id) { return knowledgeFrom(view.path("knowledge"), id); }
    private static JsonNode knowledgeFrom(JsonNode array, String id) { for (JsonNode item : array) if (item.path("factId").asText().equals(id)) return item; throw new AssertionError("缺少已知快照：" + id); }
    private static boolean hasKnowledge(JsonNode view, String id) { for (JsonNode item : view.path("knowledge")) if (item.path("factId").asText().equals(id)) return true; return false; }
    private static void unchangedOnReject(Fixture f, JsonNode p) throws Exception {
        byte[] before = Files.readAllBytes(f.path); JsonNode state = f.store.view("@author").deepCopy(); JsonNode history = f.store.history().deepCopy();
        rejects(() -> f.store.apply(p));
        check(Arrays.equals(before, Files.readAllBytes(f.path)), "拒绝行动后保存文件有变化");
        equal(f.store.view("@author"), state, "拒绝行动后内存资料有变化"); equal(f.store.history(), history, "拒绝行动后历史有变化");
    }
    private static void rejects(CheckedAction action) throws Exception {
        try { action.run(); } catch (IllegalArgumentException | IllegalStateException | java.io.IOException expected) { return; }
        throw new AssertionError("应被拒绝的操作被执行");
    }
    private static void run(String name, CheckedAction action) {
        ObjectNode r = JSON.createObjectNode(); r.put("name", name);
        try { action.run(); r.put("passed", true); }
        catch (Exception | AssertionError failure) { r.put("passed", false); r.put("error", failure.getClass().getSimpleName() + ": " + failure.getMessage()); }
        RESULTS.add(r);
    }
    private static void equal(Object actual, Object expected, String why) { check(java.util.Objects.equals(actual, expected), why + "；预期=" + expected + "；实际=" + actual); }
    private static void check(boolean condition, String why) { if (!condition) throw new AssertionError(why); }
    @FunctionalInterface private interface CheckedCondition { boolean get() throws Exception; }
    @FunctionalInterface private interface CheckedAction { void run() throws Exception; }
}
