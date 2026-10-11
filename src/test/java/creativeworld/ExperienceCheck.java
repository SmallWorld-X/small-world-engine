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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static creativeworld.ExperienceComponent.Stage;

/** 五阶段本地声明策略、短锁及恢复边界；不调用模型或评价小说质量。 */
public final class ExperienceCheck {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<ObjectNode> RESULTS = new ArrayList<>();
    private static Path testRoot, examples;
    private static int number;

    public static void main(String[] args) throws Exception {
        testRoot = Files.createTempDirectory("creative-world-experience-check-");
        examples = args.length > 1 ? Path.of(args[1]) : Path.of("examples");
        if (args.length < 2 && !Files.exists(examples.resolve("experience-ask.json")))
            examples = Path.of("creative-world-engine", "examples");

        run("一个有依据的小事通过五阶段，产生有来源认识而非虚构运送", () -> {
            Fixture f = fresh(); ObjectNode input = request(f, "ask-location"); Path recordPath = next("experience");
            ObjectNode record = new ExperienceEngine().run(f.store, input, recordPath);
            equal(record.path("status").asText(), "completed", "五阶段完成");
            equal(record.path("stages").size(), 5, "五个实际阶段输出");
            for (Stage stage : Stage.values()) {
                JsonNode item = completedStage(record, stage);
                check(!item.path("componentId").asText().isBlank(), "阶段有组件身份");
                equal(item.path("componentVersion").asText(), "1", "默认版本");
                equal(item.path("output").path("status").asText(), "ready", "阶段确实就绪");
            }
            equal(f.store.inspect().path("revision").asInt(), 1, "只提交一个世界事件");
            JsonNode known = knowledge(f.store.view("apprentice"), "cargo-location");
            equal(known.path("value").asText(), "warehouse", "学徒得知来源人的位置消息");
            equal(known.path("source").asText(), "boatman", "认识有船工来源");
            equal(byId(f.store.view("@author").path("characters"), "apprentice").path("place").asText(), "wharf", "询问没有被写成取货或移动");
            equal(byId(f.store.view("@author").path("facts"), "cargo-location").path("value").asText(), "warehouse", "询问不改变世界位置");
            equal(record.path("receipt").path("revision").asInt(), 1, "记录绑定真正提交回执");
            equal(record.path("finalView"), f.store.viewAt("apprentice", 1), "反馈使用提交后权威人物投影");
            equal(completedStage(record, Stage.FEEDBACK).path("output").path("receipt"), record.path("receipt"), "反馈回执一致");
            equal(completedStage(record, Stage.FEEDBACK).path("output").path("remainingGaps"), input.path("scenario").path("remainingGaps"), "剩余缺口没有被一次提交消除");
            check(!record.path("initialView").has("facts") && !record.path("initialView").has("entities"), "阶段输入不是作者全量资料");
            equal(JSON.readTree(recordPath.toFile()), record, "演绎记录实际持久化");
        });

        run("缺准备依据停为 needsInput，保留缺口且正式世界不变", () -> {
            Fixture f = fresh(); ObjectNode input = read("experience-missing.json"); input.put("requestId", "missing-permission");
            byte[] before = Files.readAllBytes(f.path); JsonNode history = f.store.history().deepCopy(); Path path = next("missing");
            ObjectNode record = new ExperienceEngine().run(f.store, input, path);
            equal(record.path("status").asText(), "needsInput", "缺资料不能伪称成功");
            equal(record.path("stages").size(), 1, "缺准备时不能调用候选与行动阶段");
            JsonNode gaps = completedStage(record, Stage.PREPARE).path("output").path("gaps");
            check(hasPath(gaps, "/self/privateProfile/cargoPermission"), "明确指出真正缺少的许可依据");
            check(!record.has("receipt") && !record.has("prepared"), "停住时没有提交或待提交动作");
            check(Arrays.equals(before, Files.readAllBytes(f.path)), "缺资料时世界文件不变");
            equal(f.store.history(), history, "缺资料时历史不变");
            equal(JSON.readTree(path.toFile()), record, "缺口记录已保存");
        });

        run("所有阶段上下文均为快照，组件修改不能污染下一阶段或权威世界", () -> {
            Fixture f = fresh(); ObjectNode input = request(f, "snapshot-isolation"), original = input.deepCopy();
            Map<Stage, AtomicInteger> calls = counters(); ExperienceEngine engine = instrumented("1", calls, (base, context, count) -> {
                ObjectNode seenRequest = context.request(), seenView = context.actorView(), seenOutputs = context.outputs(), seenReceipt = context.receipt();
                context.request().remove("actor"); context.actorView().remove("self"); context.outputs().removeAll(); context.receipt().put("revision", 999);
                equal(context.request(), seenRequest, "请求 getter 没有共享树");
                equal(context.actorView(), seenView, "人物视图 getter 没有共享树");
                equal(context.outputs(), seenOutputs, "前阶段结果 getter 没有共享树");
                equal(context.receipt(), seenReceipt, "回执 getter 没有共享树");
                check(!seenView.has("characters") && !seenView.has("facts"), "组件不得收到世界隐藏全量资料");
                return base.run(context);
            });
            ObjectNode record = engine.run(f.store, input, next("snapshot")); equal(record.path("status").asText(), "completed", "快照改动不影响后续阶段");
            equal(input, original, "调用者原请求不受组件改动");
            for (Stage stage : Stage.values()) equal(calls.get(stage).get(), 1, "组件实际调用一次：" + stage);
            equal(knowledge(f.store.view("apprentice"), "cargo-location").path("source").asText(), "boatman", "实际动作仍是原询问");
        });

        run("组件版本可以并存，旧记录绑定原版本且完整重跑不重复提交", () -> {
            Fixture f = fresh(); Map<Stage, AtomicInteger> first = counters(), second = counters();
            ExperienceEngine engine = instrumented("1", first, (base, context, count) -> base.run(context));
            for (ExperienceComponent base : BuiltinExperienceComponents.all()) engine.register(wrapper(base, "2", second, (component, context, count) -> component.run(context)));
            ObjectNode oldRequest = request(f, "version-one"); Path oldPath = next("v1"); ObjectNode old = engine.run(f.store, oldRequest, oldPath);
            byte[] oldRecord = Files.readAllBytes(oldPath), once = Files.readAllBytes(f.path);
            equal(engine.run(f.store, oldRequest.deepCopy(), oldPath), old, "同完整输入返回原演绎结果");
            check(Arrays.equals(once, Files.readAllBytes(f.path)), "旧运行重跑不写第二个事件");
            ObjectNode nextRequest = request(f, "version-two"); versions(nextRequest, "2");
            ObjectNode next = engine.run(f.store, nextRequest, next("v2")); equal(next.path("status").asText(), "completed", "版本二独立运行");
            equal(f.store.inspect().path("revision").asInt(), 2, "新运行仅追加自己的事件");
            equal(engine.run(f.store, oldRequest, oldPath), old, "后续世界变化不暗改旧运行");
            check(Arrays.equals(oldRecord, Files.readAllBytes(oldPath)), "旧记录保持原字节");
            for (Stage stage : Stage.values()) {
                equal(first.get(stage).get(), 1, "版本一不重复执行：" + stage);
                equal(second.get(stage).get(), 1, "版本二确实执行：" + stage);
            }
            ObjectNode changedVersion = oldRequest.deepCopy(); versions(changedVersion, "2"); byte[] current = Files.readAllBytes(f.path);
            rejects(() -> engine.run(f.store, changedVersion, oldPath));
            check(Arrays.equals(current, Files.readAllBytes(f.path)) && Arrays.equals(oldRecord, Files.readAllBytes(oldPath)), "换版本不能覆盖旧运行");
            rejects(() -> engine.register(wrapper(defaultComponent(Stage.PREPARE), "1", first, (base, context, count) -> base.run(context))));
        });

        run("缺组件、重复阶段、错阶段或未注册版本在提交前拒绝", () -> {
            Fixture f = fresh(); ObjectNode missing = request(f, "missing-component"); ((ArrayNode) missing.path("components")).remove(4);
            rejectConfiguration(f, missing);
            ObjectNode duplicate = request(f, "duplicate-stage"); ArrayNode rows = (ArrayNode) duplicate.path("components"); rows.set(1, rows.get(0).deepCopy());
            rejectConfiguration(f, duplicate);
            ObjectNode wrong = request(f, "wrong-stage"); ((ObjectNode) wrong.path("components").get(2)).put("id", "declared-candidates"); rejectConfiguration(f, wrong);
            ObjectNode unknown = request(f, "unknown-version"); ((ObjectNode) unknown.path("components").get(0)).put("version", "999"); rejectConfiguration(f, unknown);
        });

        run("选择不存在候选或转换为另一个动作都失败并保存原因", () -> {
            Fixture selection = fresh(); ExperienceEngine badSelect = new ExperienceEngine();
            badSelect.register(component("nonexistent-selection", "1", Stage.SELECT, context -> JSON.createObjectNode().put("status", "ready").put("candidateId", "invented-candidate").put("reason", "不属于原候选")));
            ObjectNode selected = request(selection, "bad-selection"); configure(selected, Stage.SELECT, "nonexistent-selection", "1");
            failureBeforeCommit(selection, badSelect, selected, Stage.SELECT);
            Fixture resolve = fresh(); ExperienceEngine badResolve = new ExperienceEngine();
            badResolve.register(component("changed-action", "1", Stage.RESOLVE, context -> {
                ObjectNode result = JSON.createObjectNode().put("status", "ready"); result.putObject("action").put("module", "travel").putObject("arguments").put("to", "warehouse"); return result;
            }));
            ObjectNode changed = request(resolve, "bad-resolution"); configure(changed, Stage.RESOLVE, "changed-action", "1");
            failureBeforeCommit(resolve, badResolve, changed, Stage.RESOLVE);
        });

        run("候选、行动与行为模块都不能把人物身份伪装成作者", () -> {
            Fixture candidate = fresh(); ObjectNode spoofed = request(candidate, "spoofed-candidate");
            ((ObjectNode) spoofed.path("scenario").path("candidates").get(0).path("arguments")).put("actor", "@author");
            failureBeforeCommit(candidate, new ExperienceEngine(), spoofed, Stage.PREPARE);
            Fixture action = fresh(); ExperienceEngine resolver = new ExperienceEngine();
            resolver.register(component("spoofed-action", "1", Stage.RESOLVE, context -> {
                ObjectNode result = defaultComponent(Stage.RESOLVE).run(context);
                ((ObjectNode) result.path("action").path("arguments")).put("actor", "@author"); return result;
            }));
            ObjectNode injected = request(action, "spoofed-action"); configure(injected, Stage.RESOLVE, "spoofed-action", "1");
            failureBeforeCommit(action, resolver, injected, Stage.RESOLVE);
            Fixture module = fresh(); module.store.register(new ActionModule() {
                public String id() { return "upgrade-author"; }
                public JsonNode propose(JsonNode view, JsonNode input) {
                    ObjectNode proposal = actionProposal(view, input, belief("apprentice", "不应提交")); proposal.put("actor", "@author"); return proposal;
                }
            });
            ObjectNode moduleRequest = request(module, "spoofed-module"); ((ObjectNode) moduleRequest.path("scenario").path("candidates").get(0)).put("module", "upgrade-author");
            byte[] bytes = Files.readAllBytes(module.path); Path path = next("spoofed-module");
            rejects(() -> new ExperienceEngine().run(module.store, moduleRequest, path));
            check(Arrays.equals(bytes, Files.readAllBytes(module.path)), "行为模块身份升级不得落账");
            ObjectNode failure = (ObjectNode) JSON.readTree(path.toFile()); equal(failure.path("status").asText(), "failed", "提交前失败不能伪称反馈失败");
            check(!failure.has("receipt") && failure.has("error"), "没有伪造提交回执");
        });

        run("反馈失败保留已提交事件，恢复只补反馈并绑定原提交版本", () -> {
            Fixture f = fresh(); Map<Stage, AtomicInteger> calls = counters();
            ExperienceEngine engine = instrumented("1", calls, (base, context, count) -> {
                if (base.stage() == Stage.FEEDBACK && count == 1) throw new IllegalStateException("本地反馈首次失败");
                return base.run(context);
            });
            ObjectNode input = request(f, "recover-feedback"); Path path = next("feedback"); rejects(() -> engine.run(f.store, input, path));
            JsonNode failed = JSON.readTree(path.toFile()); equal(failed.path("status").asText(), "feedbackFailed", "提交后失败状态");
            equal(failed.path("receipt").path("revision").asInt(), 1, "提交回执没有丢失");
            equal(f.store.history().path("events").size(), 1, "反馈错误不撤销真正事件");
            check(hasFailedStage(failed, Stage.FEEDBACK), "保留失败阶段记录");
            f.store.apply(authorProposal(f, "another-forward-event", belief("boatman", "后来又有一条私有记录")));
            byte[] beforeRecovery = Files.readAllBytes(f.path);
            ObjectNode recovered = engine.run(f.store, input.deepCopy(), path);
            equal(recovered.path("status").asText(), "completed", "反馈可恢复");
            equal(recovered.path("receipt"), failed.path("receipt"), "恢复沿用原权威回执");
            equal(recovered.path("finalView").path("revision").asInt(), 1, "反馈不能混入提交之后的其他事件");
            equal(f.store.inspect().path("revision").asInt(), 2, "恢复不重复动作");
            check(Arrays.equals(beforeRecovery, Files.readAllBytes(f.path)), "反馈恢复不写正式世界");
            for (Stage stage : Stage.values()) equal(calls.get(stage).get(), stage == Stage.FEEDBACK ? 2 : 1, "仅反馈被重试：" + stage);
            check(hasFailedStage(recovered, Stage.FEEDBACK), "恢复后保留首次失败证据");
            equal(recovered.path("stages").size(), 6, "失败项与完成项均保留");
            equal(engine.run(f.store, input, path), recovered, "恢复完成后重跑命中原结果");
            equal(calls.get(Stage.FEEDBACK).get(), 2, "完成后不再调用反馈组件");
        });

        run("提交前阶段失败落记录，恢复保留已完成阶段并继续原尝试", () -> {
            Fixture f = fresh(); Map<Stage, AtomicInteger> calls = counters();
            ExperienceEngine engine = instrumented("1", calls, (base, context, count) -> {
                if (base.stage() == Stage.CANDIDATES && count == 1) throw new IllegalStateException("候选阶段首次失败");
                return base.run(context);
            });
            ObjectNode input = request(f, "recover-candidates"); Path path = next("phase-failure"); byte[] before = Files.readAllBytes(f.path);
            rejects(() -> engine.run(f.store, input, path)); JsonNode failed = JSON.readTree(path.toFile());
            equal(failed.path("status").asText(), "failed", "提交前阶段失败状态"); check(hasFailedStage(failed, Stage.CANDIDATES), "失败原因有对应阶段");
            check(!failed.has("receipt") && Arrays.equals(before, Files.readAllBytes(f.path)), "失败阶段尚未改变世界");
            ObjectNode recovered = engine.run(f.store, input, path); equal(recovered.path("status").asText(), "completed", "阶段恢复成功");
            equal(calls.get(Stage.PREPARE).get(), 1, "准备成功结果不重算"); equal(calls.get(Stage.CANDIDATES).get(), 2, "只重新调用失败阶段");
            for (Stage stage : List.of(Stage.SELECT, Stage.RESOLVE, Stage.FEEDBACK)) equal(calls.get(stage).get(), 1, "后阶段首次执行：" + stage);
            check(hasFailedStage(recovered, Stage.CANDIDATES), "恢复后失败历史仍在");
            equal(f.store.history().path("events").size(), 1, "整个尝试只产生一个事件");
        });

        run("两个旧版本待提交提案竞争时只有一个落账", () -> {
            Fixture f = fresh(); ObjectNode first = moduleInput(f, "prepared-first"), second = moduleInput(f, "prepared-second");
            first.put("target", "boatman").put("factId", "cargo-location"); second.put("target", "boatman").put("factId", "cargo-location");
            JsonNode p1 = f.store.propose("ask", first), p2 = f.store.propose("ask", second);
            equal(f.store.inspect().path("revision").asInt(), 0, "准备提案不提前提交");
            JsonNode receipt = f.store.commitPrepared(p1); equal(receipt.path("revision").asInt(), 1, "第一个竞争提案提交");
            byte[] bytes = Files.readAllBytes(f.path); JsonNode history = f.store.history().deepCopy();
            rejects(() -> f.store.commitPrepared(p2)); check(Arrays.equals(bytes, Files.readAllBytes(f.path)), "旧提案不能覆盖获胜事件");
            equal(f.store.history(), history, "竞争失败不产生额外历史");
            equal(f.store.commitPrepared(p1), receipt, "同一prepared恢复回执");
        });

        run("待提交包身份受约束，已提交回执必须来自正式事件", () -> {
            Fixture f = fresh(); ObjectNode input = moduleInput(f, "prepared-authority");
            input.put("target", "boatman").put("factId", "cargo-location");
            ObjectNode prepared = (ObjectNode) f.store.propose("ask", input); byte[] original = Files.readAllBytes(f.path);
            ObjectNode upgraded = prepared.deepCopy(); ((ObjectNode) upgraded.path("proposal")).put("actor", "@author");
            rejects(() -> f.store.commitPrepared(upgraded));
            ObjectNode forged = JSON.createObjectNode().put("schemaVersion", 1).put("status", "alreadyCommitted");
            forged.set("submission", prepared.path("submission").deepCopy());
            forged.putObject("receipt").put("schemaVersion", 1).put("worldId", input.path("worldId").asText())
                .put("requestId", input.path("requestId").asText()).put("revision", 99).put("status", "committed");
            rejects(() -> f.store.commitPrepared(forged));
            check(Arrays.equals(original, Files.readAllBytes(f.path)), "无正式事件时伪回执或升级身份均不落账");
            JsonNode actual = f.store.commitPrepared(prepared); ObjectNode replay = (ObjectNode) f.store.propose("ask", input);
            equal(replay.path("status").asText(), "alreadyCommitted", "相同完整输入找到真正事件");
            ((ObjectNode) replay.path("receipt")).put("revision", 99); byte[] committed = Files.readAllBytes(f.path);
            equal(f.store.commitPrepared(replay), actual, "不信任包内版本而返回真正回执");
            check(Arrays.equals(committed, Files.readAllBytes(f.path)), "回执核对不重复提交");
        });

        run("阶段缺口已保存但外层仍 running 时，恢复仍停住且不调用后阶段", () -> {
            Fixture f = fresh(); ObjectNode input = read("experience-missing.json"); input.put("requestId", "stop-after-checkpoint");
            Path path = next("checkpoint-gap"); ObjectNode checkpoint = new ExperienceEngine().run(f.store, input, path);
            equal(checkpoint.path("status").asText(), "needsInput", "原准备确实缺资料");
            checkpoint.put("status", "running"); JSON.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), checkpoint);
            byte[] bytes = Files.readAllBytes(f.path); Map<Stage, AtomicInteger> calls = counters();
            ExperienceEngine engine = instrumented("1", calls, (base, context, count) -> base.run(context));
            ObjectNode restored = engine.run(f.store, input, path);
            equal(restored.path("status").asText(), "needsInput", "外层未保存不能掩盖阶段缺口");
            equal(restored.path("stages").size(), 1, "恢复不会越过已存的停止阶段");
            for (Stage stage : Stage.values()) equal(calls.get(stage).get(), 0, "恢复停止不再次执行组件：" + stage);
            check(!restored.has("prepared") && !restored.has("receipt") && Arrays.equals(bytes, Files.readAllBytes(f.path)), "停止恢复没有偷跑正式动作");
        });

        run("完成记录缺权威资料、改反馈或改历史阶段均不能当作成功重放", () -> {
            Fixture f = fresh(); ObjectNode input = request(f, "completed-record-guard"); Path path = next("completed-record");
            ObjectNode completed = new ExperienceEngine().run(f.store, input, path);
            ObjectNode missingReceipt = completed.deepCopy(); missingReceipt.remove("receipt"); rejectTamperedRecord(f, input, path, missingReceipt);
            ObjectNode falseFeedback = completed.deepCopy();
            ((ObjectNode) completedStage(falseFeedback, Stage.FEEDBACK).path("output").path("after").path("self")).put("place", "forge");
            rejectTamperedRecord(f, input, path, falseFeedback);
            ObjectNode noncanonicalStage = completed.deepCopy(); ((ObjectNode) noncanonicalStage.path("stages").get(0)).put("stage", "PREPARE");
            rejectTamperedRecord(f, input, path, noncanonicalStage);
            ObjectNode duplicateStage = completed.deepCopy(); ((ArrayNode) duplicateStage.path("stages")).add(completed.path("stages").get(0).deepCopy());
            rejectTamperedRecord(f, input, path, duplicateStage);
            JSON.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), completed);
            equal(new ExperienceEngine().run(f.store, input, path), completed, "合法原记录仍可核对并重放");
        });

        run("五阶段已存但外层仍 running 时，不能跳过权威完成检查", () -> {
            Fixture f = fresh(); ObjectNode input = request(f, "five-stage-running"); Path path = next("running-complete");
            ObjectNode completed = new ExperienceEngine().run(f.store, input, path); byte[] world = Files.readAllBytes(f.path);
            List<ObjectNode> invalid = new ArrayList<>();
            for (String field : List.of("prepared", "receipt", "finalView")) {
                ObjectNode checkpoint = completed.deepCopy(); checkpoint.put("status", "running"); checkpoint.remove(field); invalid.add(checkpoint);
            }
            ObjectNode changedFeedback = completed.deepCopy(); changedFeedback.put("status", "running");
            ((ObjectNode) completedStage(changedFeedback, Stage.FEEDBACK).path("output").path("after").path("self")).put("place", "forge");
            invalid.add(changedFeedback);
            for (ObjectNode checkpoint : invalid) {
                JSON.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), checkpoint); Map<Stage, AtomicInteger> calls = counters();
                ExperienceEngine engine = instrumented("1", calls, (base, context, count) -> base.run(context));
                rejects(() -> engine.run(f.store, input, path)); JsonNode failure = JSON.readTree(path.toFile());
                check(!failure.path("status").asText().equals("completed") && failure.has("error"), "完整阶段不等于权威完成状态");
                check(Arrays.equals(world, Files.readAllBytes(f.path)), "恢复拒绝不更改已提交世界");
                for (Stage stage : Stage.values()) equal(calls.get(stage).get(), 0, "缓存阶段不应为弥补伪资料而重新执行：" + stage);
            }
            completed.put("status", "running"); JSON.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), completed);
            equal(new ExperienceEngine().run(f.store, input, path).path("status").asText(), "completed", "资料完整的真实崩溃窗口可恢复完成");
            check(Arrays.equals(world, Files.readAllBytes(f.path)), "合法恢复也不再次提交");
        });

        run("四阶段有效 prepared 尚未提交时，伪造已保存回执必须先于落账拒绝", () -> {
            Fixture reference = fresh(); ObjectNode input = request(reference, "receipt-before-commit");
            ObjectNode checkpoint = new ExperienceEngine().run(reference.store, input, next("reference-completed"));
            Fixture f = fresh(); checkpoint.put("worldFile", f.store.path().toString()).put("status", "running");
            ((ArrayNode) checkpoint.path("stages")).remove(4); checkpoint.remove("finalView");
            ((ObjectNode) checkpoint.path("receipt")).put("revision", 99);
            Path path = next("uncommitted-false-receipt"); JSON.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), checkpoint);
            byte[] world = Files.readAllBytes(f.path); JsonNode history = f.store.history().deepCopy(); Map<Stage, AtomicInteger> calls = counters();
            ExperienceEngine engine = instrumented("1", calls, (base, context, count) -> base.run(context));
            rejects(() -> engine.run(f.store, input, path)); JsonNode failure = JSON.readTree(path.toFile());
            check(!failure.path("status").asText().equals("completed") && failure.has("error"), "伪回执不能当成一次真实提交");
            check(Arrays.equals(world, Files.readAllBytes(f.path)), "必须在首次落账前识别伪回执，不能先提交后报错");
            equal(f.store.history(), history, "正式事件台账仍然为空");
            for (Stage stage : Stage.values()) equal(calls.get(stage).get(), 0, "无对应正式事件时不进入反馈组件：" + stage);
            ObjectNode valid = (ObjectNode) failure.deepCopy(); valid.remove("receipt"); valid.remove("error"); valid.put("status", "running");
            JSON.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), valid);
            ObjectNode recovered = engine.run(f.store, input, path);
            equal(recovered.path("status").asText(), "completed", "同一有效 prepared 去掉伪回执后能够正常提交");
            equal(f.store.inspect().path("revision").asInt(), 1, "有效动作仅追加一次");
            equal(calls.get(Stage.FEEDBACK).get(), 1, "合法提交之后才执行反馈");
            for (Stage stage : List.of(Stage.PREPARE, Stage.CANDIDATES, Stage.SELECT, Stage.RESOLVE)) equal(calls.get(stage).get(), 0, "有效前阶段缓存仍然复用：" + stage);
        });

        run("未提交 prepared 被改成其他合法动作时，也不能绕过所选候选", () -> {
            Fixture f = fresh(); f.store.register(new ActionModule() {
                public String id() { return "declared-trip"; }
                public JsonNode propose(JsonNode view, JsonNode input) {
                    return actionProposal(view, input, JSON.createObjectNode().put("type", "person.move").put("personId", "apprentice")
                        .put("expectedPlace", "wharf").put("to", input.path("to").asText()));
                }
            });
            ObjectNode input = request(f, "prepared-selected-guard"); ObjectNode candidate = (ObjectNode) input.path("scenario").path("candidates").get(0);
            candidate.put("module", "declared-trip"); candidate.putObject("arguments").put("to", "forge");
            Path path = next("prepared-selection"); byte[] bytes = Files.readAllBytes(f.path);
            ExperienceEngine engine = new ExperienceEngine(); rejects(() -> engine.run(f.store, input, path));
            ObjectNode failed = (ObjectNode) JSON.readTree(path.toFile());
            check(failed.has("prepared") && !failed.has("receipt"), "不允许直跳时只保存未提交包");
            check(Arrays.equals(bytes, Files.readAllBytes(f.path)), "非法路径动作没有改变世界");
            ((ObjectNode) failed.path("prepared").path("submission").path("input")).put("to", "warehouse");
            ((ObjectNode) failed.path("prepared").path("proposal").path("effects").get(0)).put("to", "warehouse");
            JSON.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), failed);
            rejects(() -> engine.run(f.store, input, path));
            check(Arrays.equals(bytes, Files.readAllBytes(f.path)), "改为内核允许的路径也不能偏离本次已选候选落账");
            equal(f.store.inspect().path("revision").asInt(), 0, "未提交尝试没有伪事件");
        });

        run("新反馈组件不能把伪回执或伪人物状态写成实际效果", () -> {
            for (boolean forgedReceipt : List.of(true, false)) {
                Fixture f = fresh(); ExperienceEngine engine = new ExperienceEngine();
                engine.register(component("invented-feedback", "1", Stage.FEEDBACK, context -> {
                    ObjectNode output = defaultComponent(Stage.FEEDBACK).run(context);
                    if (forgedReceipt) ((ObjectNode) output.path("receipt")).put("revision", 999);
                    else ((ObjectNode) output.path("after").path("self")).put("place", "forge");
                    return output;
                }));
                ObjectNode input = request(f, forgedReceipt ? "invented-receipt" : "invented-after");
                configure(input, Stage.FEEDBACK, "invented-feedback", "1"); Path path = next("feedback-authority");
                rejects(() -> engine.run(f.store, input, path)); JsonNode record = JSON.readTree(path.toFile());
                equal(record.path("status").asText(), "feedbackFailed", "反馈失真不能报完成");
                equal(record.path("receipt").path("revision").asInt(), 1, "保留实际已提交回执");
                equal(record.path("finalView"), f.store.viewAt("apprentice", 1), "记录实际状态未被反馈改写");
                equal(f.store.history().path("events").size(), 1, "反馈问题不会删除或再提交正式事件");
                check(hasFailedStage(record, Stage.FEEDBACK), "反馈失真有失败阶段证据");
            }
        });

        run("慢行为模块不占世界读取锁，等待期间修改输入不改实际提交", () -> {
            Fixture f = fresh(); CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1); AtomicInteger calls = new AtomicInteger();
            f.store.register(new ActionModule() {
                public String id() { return "slow-notice"; }
                public JsonNode propose(JsonNode view, JsonNode input) {
                    calls.incrementAndGet(); entered.countDown(); await(release, "等待慢动作释放");
                    return actionProposal(view, input, belief(input.path("actor").asText(), input.path("note").asText()));
                }
            });
            ObjectNode input = moduleInput(f, "slow-original"); input.put("note", "原输入内容"); ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Future<JsonNode> pending = pool.submit(() -> f.store.propose("slow-notice", input)); await(entered, "慢动作应开始");
                Future<JsonNode> read = pool.submit(() -> new WorldStore(f.path).inspect());
                equal(read.get(3, TimeUnit.SECONDS).path("revision").asInt(), 0, "模块执行期间世界可读取");
                input.put("requestId", "changed-while-waiting").put("actor", "@author").put("note", "被调用者后来改过的内容");
                release.countDown(); JsonNode prepared = pending.get(5, TimeUnit.SECONDS); JsonNode receipt = f.store.commitPrepared(prepared);
                equal(receipt.path("requestId").asText(), "slow-original", "准备入口保留原请求身份");
                equal(f.store.view("apprentice").path("beliefs").get(0).path("text").asText(), "原输入内容", "等待期间改输入不改变真实参数");
                ObjectNode replay = moduleInput(f, "slow-original"); replay.put("expectedRevision", 0).put("note", "原输入内容");
                equal(f.store.commitPrepared(f.store.propose("slow-notice", replay)), receipt, "重复准备返回原提交回执");
                equal(calls.get(), 1, "已提交重放不再次调用慢模块");
            } finally { release.countDown(); stop(pool); }
        });

        run("慢准备阶段不持世界锁，外部修改请求不改变本次演绎", () -> {
            Fixture f = fresh(); CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1); ExperienceEngine engine = new ExperienceEngine();
            engine.register(component("held-preparation", "1", Stage.PREPARE, context -> {
                entered.countDown(); await(release, "等待准备阶段释放"); return defaultComponent(Stage.PREPARE).run(context);
            }));
            ObjectNode input = request(f, "held-original"); configure(input, Stage.PREPARE, "held-preparation", "1"); ObjectNode original = input.deepCopy();
            Path path = next("held"); ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Future<ObjectNode> pending = pool.submit(() -> engine.run(f.store, input, path)); await(entered, "准备阶段应开始");
                Future<JsonNode> read = pool.submit(() -> new WorldStore(f.path).view("apprentice"));
                equal(read.get(3, TimeUnit.SECONDS).path("revision").asInt(), 0, "演绎组件期间世界可读取");
                input.put("requestId", "held-mutated").put("actor", "@author").put("intent", "被改成别的目的");
                ((ObjectNode) input.path("scenario").path("candidates").get(0).path("arguments")).put("factId", "unknown-secret");
                release.countDown(); ObjectNode record = pending.get(5, TimeUnit.SECONDS);
                equal(record.path("status").asText(), "completed", "外部改树不改变原演绎");
                equal(record.path("request"), original, "运行绑定原请求完整快照");
                equal(record.path("receipt").path("requestId").asText(), "held-original", "提交原身份");
                equal(knowledge(f.store.view("apprentice"), "cargo-location").path("source").asText(), "boatman", "提交原候选内容");
            } finally { release.countDown(); stop(pool); }
        });

        run("演绎记录不能覆盖正式世界文件，候选没有依据时不提交", () -> {
            Fixture f = fresh(); ObjectNode input = request(f, "same-record-and-world"); byte[] before = Files.readAllBytes(f.path);
            rejects(() -> new ExperienceEngine().run(f.store, input, f.path)); check(Arrays.equals(before, Files.readAllBytes(f.path)), "记录路径保护正式世界");
            ObjectNode missing = request(f, "candidate-missing-evidence"); JsonNode candidate = missing.path("scenario").path("candidates").get(0);
            ((ObjectNode) candidate).putArray("evidence").addObject().put("path", "/self/privateProfile/cargoPermission").put("label", "尚未提供的许可");
            Path path = next("candidate-missing"); ObjectNode stopped = new ExperienceEngine().run(f.store, missing, path);
            equal(stopped.path("status").asText(), "needsInput", "候选缺依据也不能继续");
            equal(stopped.path("stages").size(), 2, "准备通过，候选阶段停住");
            check(!stopped.has("receipt") && Arrays.equals(before, Files.readAllBytes(f.path)), "缺候选依据未提交任何变化");
        });

        ObjectNode report = JSON.createObjectNode(); long passed = RESULTS.stream().filter(row -> row.path("passed").asBoolean()).count();
        report.put("checks", RESULTS.size()).put("passed", passed).put("failed", RESULTS.size() - passed);
        report.set("results", JSON.valueToTree(RESULTS)); report.put("actualModelCalled", false).put("formalServiceUsed", false).put("proseQualityEvaluated", false);
        report.put("evidenceLimit", "仅核对本地声明策略、五阶段承接、版本绑定、短锁和提交恢复；不证明模型推导、人物心理或小说阅读质量。");
        report.put("isolatedTestDirectory", testRoot.toString()); report.put("examplesDirectory", examples.toAbsolutePath().toString());
        if (args.length > 0) { Path output = Path.of(args[0]).toAbsolutePath(); Files.createDirectories(output.getParent()); JSON.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report); }
        System.out.println(JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report));
        if (passed != RESULTS.size()) System.exit(1);
    }

    private record Fixture(Path path, WorldStore store) {}
    private static Path next(String kind) { return testRoot.resolve(kind + "-" + ++number + ".json"); }
    private static ObjectNode read(String name) throws Exception { return (ObjectNode) JSON.readTree(examples.resolve(name).toFile()); }
    private static Fixture fresh() throws Exception {
        Path path = next("world"); WorldStore store = new WorldStore(path); store.initialize(new WorldGenesis().build(read("genesis-river-town.json")).path("seed")); return new Fixture(path, store);
    }
    private static ObjectNode request(Fixture f, String id) throws Exception {
        ObjectNode input = read("experience-ask.json"); input.put("worldId", f.store.inspect().path("worldId").asText()).put("expectedRevision", f.store.inspect().path("revision").asInt()).put("requestId", id); return input;
    }
    private static void configure(ObjectNode input, Stage stage, String id, String version) {
        for (JsonNode row : input.path("components")) if (row.path("stage").asText().equals(stage.key())) { ((ObjectNode) row).put("id", id).put("version", version); return; }
        throw new AssertionError("缺少配置阶段：" + stage);
    }
    private static void versions(ObjectNode input, String version) { for (JsonNode row : input.path("components")) ((ObjectNode) row).put("version", version); }
    private static Map<Stage, AtomicInteger> counters() { Map<Stage, AtomicInteger> result = new EnumMap<>(Stage.class); for (Stage stage : Stage.values()) result.put(stage, new AtomicInteger()); return result; }
    private static ExperienceEngine instrumented(String version, Map<Stage, AtomicInteger> calls, Hook hook) {
        ExperienceEngine engine = new ExperienceEngine(false); for (ExperienceComponent base : BuiltinExperienceComponents.all()) engine.register(wrapper(base, version, calls, hook)); return engine;
    }
    private static ExperienceComponent wrapper(ExperienceComponent base, String version, Map<Stage, AtomicInteger> calls, Hook hook) {
        return component(base.id(), version, base.stage(), context -> hook.apply(base, context, calls.get(base.stage()).incrementAndGet()));
    }
    private static ExperienceComponent component(String id, String version, Stage stage, Phase phase) {
        return new ExperienceComponent() {
            public String id() { return id; }
            public String version() { return version; }
            public Stage stage() { return stage; }
            public ObjectNode run(Context context) { return phase.apply(context); }
        };
    }
    private static ExperienceComponent defaultComponent(Stage stage) { for (ExperienceComponent base : BuiltinExperienceComponents.all()) if (base.stage() == stage) return base; throw new AssertionError("缺少默认组件"); }
    private static JsonNode completedStage(JsonNode record, Stage stage) { for (JsonNode item : record.path("stages")) if (item.path("stage").asText().equals(stage.key()) && item.path("status").asText().equals("completed")) return item; throw new AssertionError("没有完成阶段：" + stage); }
    private static boolean hasFailedStage(JsonNode record, Stage stage) { for (JsonNode item : record.path("stages")) if (item.path("stage").asText().equals(stage.key()) && item.path("status").asText().equals("failed") && item.path("error").isObject()) return true; return false; }
    private static boolean hasPath(JsonNode gaps, String path) { for (JsonNode gap : gaps) if (gap.path("path").asText().equals(path)) return true; return false; }
    private static JsonNode byId(JsonNode array, String id) { for (JsonNode value : array) if (value.path("id").asText().equals(id)) return value; throw new AssertionError("缺少資料：" + id); }
    private static JsonNode knowledge(JsonNode view, String id) { for (JsonNode value : view.path("knowledge")) if (value.path("factId").asText().equals(id)) return value; throw new AssertionError("缺少认识：" + id); }
    private static void rejectConfiguration(Fixture f, ObjectNode request) throws Exception {
        byte[] before = Files.readAllBytes(f.path); Path path = next("invalid-config"); rejects(() -> new ExperienceEngine().run(f.store, request, path));
        check(Arrays.equals(before, Files.readAllBytes(f.path)), "非法配置不改世界"); check(!Files.exists(path), "非法配置不能生成可继续的运行记录");
    }
    private static void failureBeforeCommit(Fixture f, ExperienceEngine engine, ObjectNode request, Stage stage) throws Exception {
        byte[] before = Files.readAllBytes(f.path); Path path = next("stage-rejected"); rejects(() -> engine.run(f.store, request, path));
        check(Arrays.equals(before, Files.readAllBytes(f.path)), "阶段失败不能改变世界"); JsonNode record = JSON.readTree(path.toFile());
        equal(record.path("status").asText(), "failed", "提交前阶段失败状态"); check(!record.has("receipt"), "失败不能伪造回执"); check(hasFailedStage(record, stage), "失败有阶段和原因");
    }
    private static void rejectTamperedRecord(Fixture f, ObjectNode input, Path path, ObjectNode tampered) throws Exception {
        JSON.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), tampered);
        byte[] world = Files.readAllBytes(f.path), record = Files.readAllBytes(path);
        rejects(() -> new ExperienceEngine().run(f.store, input, path));
        check(Arrays.equals(world, Files.readAllBytes(f.path)), "不可信完成记录不能改变世界");
        check(Arrays.equals(record, Files.readAllBytes(path)), "拒绝后不把不可信记录补成成功");
    }
    private static ObjectNode moduleInput(Fixture f, String id) throws Exception {
        return JSON.createObjectNode().put("schemaVersion", 1).put("worldId", f.store.inspect().path("worldId").asText())
            .put("requestId", id).put("expectedRevision", f.store.inspect().path("revision").asInt()).put("actor", "apprentice");
    }
    private static ObjectNode belief(String who, String text) { return JSON.createObjectNode().put("type", "belief.add").put("personId", who).put("text", text); }
    private static ObjectNode actionProposal(JsonNode view, JsonNode input, ObjectNode... effects) {
        ObjectNode proposal = JSON.createObjectNode(); for (String key : List.of("schemaVersion", "worldId", "requestId", "expectedRevision", "actor")) proposal.set(key, input.get(key).deepCopy());
        proposal.put("reason", "依据人物快照提出本地验证动作"); ArrayNode rows = proposal.putArray("effects"); for (ObjectNode effect : effects) rows.add(effect); return proposal;
    }
    private static ObjectNode authorProposal(Fixture f, String id, ObjectNode... effects) throws Exception {
        ObjectNode input = moduleInput(f, id); input.put("actor", "@author"); return actionProposal(f.store.view("@author"), input, effects);
    }
    private static void await(CountDownLatch latch, String why) {
        try { if (!latch.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("有限等待超时：" + why); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException("等待被中断：" + why, interrupted); }
    }
    private static void stop(ExecutorService pool) throws Exception { pool.shutdownNow(); check(pool.awaitTermination(5, TimeUnit.SECONDS), "有限并发任务应终止"); }
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
    private static void equal(Object actual, Object expected, String reason) {
        boolean same = actual instanceof JsonNode a && expected instanceof JsonNode b
            ? Protocol.canonical(a).equals(Protocol.canonical(b)) : java.util.Objects.equals(actual, expected);
        check(same, reason + "；预期=" + expected + "；实际=" + actual);
    }
    private static void check(boolean condition, String reason) { if (!condition) throw new AssertionError(reason); }
    @FunctionalInterface private interface Checked { void run() throws Exception; }
    @FunctionalInterface private interface Phase { ObjectNode apply(ExperienceComponent.Context context); }
    @FunctionalInterface private interface Hook { ObjectNode apply(ExperienceComponent base, ExperienceComponent.Context context, int count); }
}
