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
import java.util.concurrent.atomic.AtomicInteger;
import static creativeworld.ExperienceComponent.Stage;

/** 作者声明资格与消息引起实际状态后果的隔离验收；不等同于社会权力或心理推导。 */
public final class IdentityCheck {
    private static final ObjectMapper JSON = Protocol.JSON;
    private static final List<ObjectNode> RESULTS = new ArrayList<>();
    private static Path examples, directory, positiveDirectory, workshopDirectory;
    private static int number;

    public static void main(String[] args) throws Exception {
        examples = args.length > 1 ? Path.of(args[1]) : Path.of("examples");
        if (!Files.exists(examples.resolve("identity/genesis.json"))) examples = Path.of("creative-world-engine/examples");
        directory = Files.createTempDirectory("creative-world-identity-");

        run("完整世界七次事件：有依据的任命、本人校正、传述与关联登记", () -> {
            positiveDirectory = directory.resolve("positive"); Files.createDirectories(positiveDirectory);
            WorldEngine host = new WorldEngine(); ObjectNode blueprint = read("genesis.json"), original = blueprint.deepCopy();
            ObjectNode draft = host.genesis().build(blueprint);
            write(positiveDirectory.resolve("genesis.json"), blueprint); write(positiveDirectory.resolve("draft.json"), draft);
            WorldStore world = host.openWorld(positiveDirectory.resolve("world.json")); world.initialize(host.genesis().confirmSeed(draft));
            Fixture f = new Fixture(world.path(), world, blueprint.path("worldId").asText());
            JsonNode initial = world.view("@author").deepCopy(); write(positiveDirectory.resolve("initial-author.json"), initial);
            equal(initial.path("places").size(), 6, "保留六地点"); equal(initial.path("characters").size(), 6, "保留六人物");
            equal(fact(initial, "clerk-verification-role").path("value").asText(), "unassigned", "起点未任命");
            equal(fact(initial, "clerk-training").path("value").asBoolean(), true, "起点有明确培训资料");
            equal(fact(initial, "guild-verification-mandate").path("value").asBoolean(), true, "起点有明确委托资料");
            check(contains(by(initial.path("entities"), "id", "guild-staff").path("refs"), "clerk"), "既有组织成员资料实际相连");
            applySaved(f, "appoint.json");
            equal(known(world.view("clerk"), "clerk-verification-role").path("source").asText(), "@world", "本人获得任命认识");
            check(!hasKnown(world.view("keeper"), "clerk-verification-role"), "任命不自动通知保管员");
            stepSaved(f, "travel", "keeper-travel.json"); applySaved(f, "keeper-share-slip.json");
            equal(known(world.view("clerk"), "cargo-slip-code").path("source").asText(), "keeper", "触发来自实际发言者");
            equal(known(world.view("clerk"), "cargo-slip-code").path("revision").asInt(), 3, "接收版本准确");
            stepSaved(f, "message-state-response", "clerk-response.json");
            JsonNode corrected = world.history().path("events").get(3).path("proposal");
            check(corrected.path("reason").asText().contains("qualified-copy-correction"), "完整候选中选择较高优先级");
            equal(fact(world.view("@author"), "registry-copy-code").path("value").asText(), "H-17", "副本实际校正");
            equal(known(world.view("clerk"), "registry-copy-code").path("source").asText(), "@world", "改后本人现场观察");
            equal(known(world.view("keeper"), "registry-copy-code").path("source").asText(), "clerk", "另一人独立接收结果");
            equal(known(world.view("keeper"), "registry-copy-code").path("revision").asInt(), 4, "传述不改原始接收版本");
            stepSaved(f, "travel", "clerk-return-warehouse.json"); stepSaved(f, "travel", "keeper-return-warehouse.json");
            stepSaved(f, "message-state-response", "keeper-response.json");
            equal(revision(f), 7, "七次正式事件，不伪造自动级联");
            JsonNode registered = world.history().path("events").get(6).path("proposal");
            equal(registered.path("effects").get(0).path("type").asText(), "fact.change", "先变化登记");
            equal(registered.path("effects").get(1).path("type").asText(), "fact.change", "关联交接变化同一包");
            equal(registered.path("effects").get(2).path("type").asText(), "knowledge.learn", "统一变化后再观察");
            equal(registered.path("effects").get(4).path("type").asText(), "knowledge.share", "本人新认识再分享");
            equal(fact(world.view("@author"), "cargo-registration-state").path("value").asText(), "registered", "登记实际完成");
            equal(fact(world.view("@author"), "cargo-handoff-status").path("value").asText(), "registered-awaiting-permission", "仍待货主许可");
            for (String factId : List.of("cargo-registration-state", "cargo-handoff-status")) {
                equal(known(world.view("keeper"), factId).path("source").asText(), "@world", "保管员现场认识");
                equal(known(world.view("clerk"), factId).path("source").asText(), "keeper", "登记员收到分享");
                equal(known(world.view("clerk"), factId).path("revision").asInt(), 7, "分享来自实际事件");
            }
            equal(world.view("keeper").path("self").path("place").asText(), "warehouse", "回到货栈现场");
            equal(world.view("clerk").path("self").path("place").asText(), "warehouse", "分享双方同地");
            noDelivery(f); stable(initial, world.view("@author")); privacy(f, world.view("keeper")); privacy(f, world.view("clerk"));
            equal(fact(world.viewAt("@author", 0), "clerk-verification-role").path("value").asText(), "unassigned", "旧资格未重写");
            equal(fact(world.viewAt("@author", 0), "registry-copy-code").path("value").asText(), "H-16", "过去副本保留");
            equal(blueprint, original, "构造未修改输入");
            write(positiveDirectory.resolve("history.json"), world.history()); write(positiveDirectory.resolve("final-author.json"), world.view("@author"));
            write(positiveDirectory.resolve("final-keeper.json"), world.view("keeper"));
        });

        run("缺引用、未知或不符前提不能靠作者说明任命", () -> {
            ObjectNode missing = seed(); removeBy((ArrayNode) missing.path("facts"), "id", "clerk-training");
            Path absent = next("missing-basis"); rejects(() -> new WorldStore(absent).initialize(missing)); check(!Files.exists(absent), "缺前提引用不产生正式世界");
            ObjectNode blueprint = read("genesis.json"); removeBy((ArrayNode) blueprint.path("sections").path("world").path("facts"), "id", "clerk-training");
            rejects(() -> new WorldGenesis().build(blueprint));
            for (Object value : new Object[]{false, null, "   "}) {
                ObjectNode bad = seed(); fact(bad, "clerk-training").set("value", JSON.valueToTree(value)); Fixture f = fresh(bad);
                ObjectNode request = input(f, "appoint.json").put("reason", "作者写很长的任命理由也不补足不存在的合格前提");
                unchangedReject(f, () -> f.world.apply(request)); equal(fact(f.world.view("@author"), "clerk-verification-role").path("value").asText(), "unassigned", "没有半项任命");
            }
            ObjectNode bad = seed(); fact(bad, "guild-verification-mandate").put("value", false); Fixture f = fresh(bad);
            unchangedReject(f, () -> f.world.apply(input(f, "appoint.json")));
        });

        run("变化前提协议拒绝畸形声明，false与零可以是明确的条件值", () -> {
            for (int kind = 0; kind < 7; kind++) {
                ObjectNode bad = seed(); ObjectNode guard = law(bad, "role-assignment-basis");
                switch (kind) {
                    case 0 -> guard.putArray("conditions");
                    case 1 -> ((ObjectNode) guard.path("conditions").get(0)).putNull("equals");
                    case 2 -> ((ObjectNode) guard.path("conditions").get(0)).put("equals", "  ");
                    case 3 -> ((ArrayNode) guard.path("conditions")).add(guard.path("conditions").get(0).deepCopy());
                    case 4 -> guard.put("factId", "cargo-owner");
                    case 5 -> guard.putNull("whenEquals");
                    case 6 -> guard.putObject("actorCondition").put("predicate", "verification-role").put("equals", " ");
                }
                Path file = next("bad-law"); rejects(() -> new WorldStore(file).initialize(bad)); check(!Files.exists(file), "非法法则未创建正式世界");
            }
            ObjectNode accepted = seed(); ArrayNode facts = (ArrayNode) accepted.path("facts");
            facts.add(testFact("audit-pending", "audit", "pending", false)); facts.add(testFact("objection-count", "audit", "objections", 0));
            law(accepted, "role-assignment-basis").putArray("conditions").add(condition("audit-pending", false)).add(condition("objection-count", 0));
            Fixture f = fresh(accepted); f.world.apply(input(f, "appoint.json"));
            equal(fact(f.world.view("@author"), "clerk-verification-role").path("value").asText(), "registrar", "false与0不被视为未知；该反例仅验证声明值语义");
        });

        run("普通人不能自升，空理由与同包补前提均不能代替资格", () -> {
            Fixture f = fresh(); unchangedReject(f, () -> f.world.apply(proposal(f, "self-promotion", "clerk", change("clerk-verification-role", "unassigned", "registrar"))));
            ObjectNode blank = input(f, "appoint.json").put("reason", " "); unchangedReject(f, () -> f.world.apply(blank));
            ObjectNode bad = seed(); fact(bad, "clerk-training").put("value", false); Fixture absent = fresh(bad);
            unchangedReject(absent, () -> absent.world.apply(proposal(absent, "train-and-appoint-in-one", "@author", change("clerk-training", false, true), change("clerk-verification-role", "unassigned", "registrar"))));
            equal(fact(absent.world.view("@author"), "clerk-training").path("value").asBoolean(), false, "先补培训也整包回滚");
            Fixture appointed = preparedClerk();
            ObjectNode packageRequest = proposal(appointed, "copy-then-register-in-one", "clerk", change("registry-copy-code", "H-16", "H-17"), change("cargo-registration-state", "pending", "registered"));
            unchangedReject(appointed, () -> appointed.world.apply(packageRequest));
            equal(fact(appointed.world.view("@author"), "registry-copy-code").path("value").asText(), "H-16", "本包先校正不能成为后续登记的前置证据");
            Fixture grant = fresh(); unchangedReject(grant, () -> grant.world.apply(proposal(grant, "grant-and-use-role-in-one", "@author", change("clerk-verification-role", "unassigned", "registrar"), change("registry-copy-code", "H-16", "H-17"))));
        });

        run("直接提案、行为与保存候选经同一资格裁决，未任命都拒绝", () -> {
            Fixture f = fresh(); unchangedReject(f, () -> f.world.apply(proposal(f, "raw-unqualified-copy", "clerk", change("registry-copy-code", "H-16", "H-17"))));
            f.world.register(uncheckedCopy());
            unchangedReject(f, () -> f.world.step("unchecked-copy", action(f, "step-unqualified-copy", "clerk")));
            byte[] bytes = Files.readAllBytes(f.path); JsonNode prepared = f.world.propose("unchecked-copy", action(f, "prepared-unqualified-copy", "clerk"));
            equal(prepared.path("status").asText(), "prepared", "候选可保存但不等于有执行资格"); check(Arrays.equals(bytes, Files.readAllBytes(f.path)), "候选不写世界");
            unchangedReject(f, () -> new WorldStore(f.path).commitPrepared(prepared));
            unchangedReject(f, () -> f.world.apply(proposal(f, "author-has-no-person-role", "@author", change("registry-copy-code", "H-16", "H-17"))));
        });

        run("撤职保留同一人及旧认识，旧资格与旧候选不准重新办事", () -> {
            Fixture f = preparedClerk(); JsonNode oldKnown = known(f.world.view("clerk"), "clerk-verification-role").deepCopy();
            JsonNode saved = f.world.propose("message-state-response", input(f, "clerk-response.json"));
            f.world.apply(proposal(f, "withdraw-role", "@author", change("clerk-verification-role", "registrar", "unassigned")));
            equal(known(f.world.view("clerk"), "clerk-verification-role"), oldKnown, "撤职不伪造本人已收到通知");
            equal(fact(f.world.viewAt("@author", 1), "clerk-verification-role").path("value").asText(), "registrar", "任命历史仍可恢复");
            equal(fact(f.world.viewAt("@author", 4), "clerk-verification-role").path("value").asText(), "unassigned", "现资格已撤回");
            unchangedReject(f, () -> new WorldStore(f.path).commitPrepared(saved));
            ObjectNode current = input(f, "clerk-response.json").put("requestId", "still-believes-qualified");
            JsonNode candidate = f.world.propose("message-state-response", current);
            equal(candidate.path("status").asText(), "prepared", "本人旧认识仍可产生候选，不声称它是真资格");
            unchangedReject(f, () -> f.world.step("message-state-response", current));
            unchangedReject(f, () -> new WorldStore(f.path).commitPrepared(candidate));
            unchangedReject(f, () -> f.world.apply(proposal(f, "raw-after-withdrawal", "clerk", change("registry-copy-code", "H-16", "H-17"))));
            equal(new WorldEngine().openWorld(f.path).view("@author"), f.world.view("@author"), "重开恢复资格及同一人物"); noDelivery(f);
        });

        run("转述旧副本消息不自动等于现在真值，登记依据由正式提交复核", () -> {
            Fixture f = correctedClerk(); JsonNode oldMessage = known(f.world.view("keeper"), "registry-copy-code").deepCopy();
            f.world.apply(proposal(f, "qualified-later-copy-change", "clerk", change("registry-copy-code", "H-17", "H-16"), observe("clerk", "registry-copy-code")));
            equal(known(f.world.view("keeper"), "registry-copy-code"), oldMessage, "另一人仍保留旧消息");
            backToWarehouse(f); ObjectNode request = input(f, "keeper-response.json").put("requestId", "old-result-cannot-register");
            JsonNode proposed = f.world.propose("message-state-response", request); equal(proposed.path("status").asText(), "prepared", "只依据本人消息仍可提出决定");
            unchangedReject(f, () -> f.world.step("message-state-response", request)); unchangedReject(f, () -> new WorldStore(f.path).commitPrepared(proposed));
            equal(fact(f.world.view("@author"), "cargo-registration-state").path("value").asText(), "pending", "当前世界前提不符不登记");
            equal(fact(f.world.view("@author"), "cargo-handoff-status").path("value").asText(), "held-awaiting-registration", "关联交接也不半更新");
            privacy(f, f.world.view("keeper"));
        });

        run("规则选择、输入边界与旧模块语义保持：同级先前且不能手选", () -> {
            ObjectNode tied = seed(); ((ObjectNode) policy(tied, "clerk").path("rules").get(0)).put("priority", 20); Fixture f = preparedClerk(tied);
            ObjectNode request = input(f, "clerk-response.json"); f.world.step("message-state-response", request);
            check(f.world.history().path("events").get(3).path("proposal").path("reason").asText().contains("standard-copy-correction"), "同级按原数组先前规则");
            equal(relation(f.world.view("clerk"), "keeper").path("value"), policy(tied, "clerk").path("rules").get(0).path("relationshipValue"), "实际后果证明选择");
            Fixture reject = preparedClerk(); ObjectNode forced = input(reject, "clerk-response.json").put("ruleId", "standard-copy-correction");
            unchangedReject(reject, () -> reject.world.step("message-state-response", forced));
            ObjectNode old = input(reject, "clerk-response.json").put("requestId", "old-module-new-policy").put("ruleId", "qualified-copy-correction");
            unchangedReject(reject, () -> reject.world.step("message-response", old));
            unchangedReject(reject, () -> reject.world.step("message-state-response", "2", input(reject, "clerk-response.json")));
            for (int kind = 0; kind < 4; kind++) {
                ObjectNode invalid = input(reject, "clerk-response.json").put("requestId", "invalid-message-" + kind);
                switch (kind) {
                    case 0 -> ((ObjectNode) invalid.path("message")).put("receivedRevision", 99);
                    case 1 -> ((ObjectNode) invalid.path("message")).put("sourcePersonId", "@world");
                    case 2 -> invalid.put("actor", "keeper");
                    case 3 -> ((ObjectNode) invalid.path("message")).put("factId", "registry-copy-origin");
                }
                unchangedReject(reject, () -> reject.world.step("message-state-response", invalid));
            }
            Fixture far = preparedClerk(); far.world.step("travel", input(far, "keeper-return-warehouse.json"));
            unchangedReject(far, () -> far.world.step("message-state-response", input(far, "clerk-response.json")));
        });

        run("本人未知旧值、重复状态目标及幕后条件不能产生合法状态回应", () -> {
            ObjectNode unknown = seed(); removeText((ArrayNode) fact(unknown, "registry-copy-code").path("knownBy"), "clerk"); Fixture f = preparedClerk(unknown);
            unchangedReject(f, () -> f.world.propose("message-state-response", input(f, "clerk-response.json")));
            ObjectNode duplicated = seed(); for (JsonNode rule : policy(duplicated, "clerk").path("rules")) ((ArrayNode) rule.path("stateChanges")).add(rule.path("stateChanges").get(0).deepCopy());
            Fixture duplicate = preparedClerk(duplicated); unchangedReject(duplicate, () -> duplicate.world.step("message-state-response", input(duplicate, "clerk-response.json")));
            ObjectNode hidden = seed(); fact(hidden, "registry-copy-origin").put("value", true);
            for (JsonNode rule : policy(hidden, "clerk").path("rules")) ((ArrayNode) rule.path("conditions")).add(condition("registry-copy-origin", true));
            Fixture notKnown = preparedClerk(hidden); check(!hasKnown(notKnown.world.view("clerk"), "registry-copy-origin"), "幕后真值没有本人知识");
            unchangedReject(notKnown, () -> notKnown.world.propose("message-state-response", input(notKnown, "clerk-response.json")));
        });

        run("办公室不能观察货栈交接，合法变化与非法观察整包回滚", () -> {
            Fixture f = correctedClerk(); ObjectNode office = input(f, "keeper-response.json").put("requestId", "office-cannot-observe-warehouse");
            JsonNode prepared = f.world.propose("message-state-response", office);
            equal(prepared.path("proposal").path("effects").get(1).path("factId").asText(), "cargo-handoff-status", "候选包含关联交接");
            unchangedReject(f, () -> f.world.step("message-state-response", office)); unchangedReject(f, () -> new WorldStore(f.path).commitPrepared(prepared));
            equal(fact(f.world.view("@author"), "cargo-registration-state").path("value").asText(), "pending", "先执行登记也整体回滚");
            equal(known(f.world.view("keeper"), "cargo-registration-state").path("value").asText(), "pending", "没有半项新认识");
            backToWarehouse(f); f.world.step("message-state-response", input(f, "keeper-response.json"));
            equal(fact(f.world.view("@author"), "cargo-registration-state").path("value").asText(), "registered", "回到合法地点同一策略才可执行");
            ObjectNode noObservation = seed(); fact(noObservation, "registry-copy-code").putArray("observableAt").add("forge"); Fixture wrong = preparedClerk(noObservation);
            unchangedReject(wrong, () -> wrong.world.step("message-state-response", input(wrong, "clerk-response.json")));
        });

        run("重开重复回执不追加事件，另一人物与地点命名也使用同一策略模块", () -> {
            Fixture f = correctedClerk(); backToWarehouse(f); ObjectNode keeper = input(f, "keeper-response.json"); JsonNode receipt = f.world.step("message-state-response", "1", keeper);
            byte[] bytes = Files.readAllBytes(f.path); WorldStore reopened = new WorldEngine().openWorld(f.path);
            equal(reopened.step("message-state-response", keeper), receipt, "旧入口固定1恢复显式1回执");
            equal(reopened.receiptFor("message-state-response", "1", keeper), receipt, "查权威回执");
            equal(reopened.view("@author"), f.world.view("@author"), "正式重放完整状态");
            unchangedReject(f, () -> reopened.step("message-state-response", "2", keeper));
            check(Arrays.equals(bytes, Files.readAllBytes(f.path)), "重复和错版均不写世界"); equal(reopened.history().path("events").size(), 7, "没有重复登记");
            Map<String, String> names = Map.of("identity-river-town", "identity-other-town", "clerk", "verifier", "keeper", "custodian", "guild-office", "registry", "warehouse", "storeyard", "registry-copy-code", "register-tag", "cargo-slip-code", "manifest-tag", "clerk-verification-role", "credential");
            ObjectNode otherSeed = (ObjectNode) remap(seed(), names); Fixture other = fresh(otherSeed);
            for (String file : List.of("appoint.json", "keeper-travel.json", "keeper-share-slip.json", "clerk-response.json", "clerk-return-warehouse.json", "keeper-return-warehouse.json", "keeper-response.json")) {
                ObjectNode in = (ObjectNode) remap(read(file), names); in.put("expectedRevision", revision(other));
                if (file.equals("appoint.json") || file.equals("keeper-share-slip.json")) other.world.apply(in);
                else other.world.step(file.contains("response") ? "message-state-response" : "travel", in);
            }
            equal(fact(other.world.view("@author"), "register-tag").path("value").asText(), "H-17", "不同物品事实命名照常校正");
            equal(known(other.world.view("custodian"), "register-tag").path("source").asText(), "verifier", "来源使用新人物身份");
            equal(other.world.view("custodian").path("self").path("place").asText(), "storeyard", "不同地点命名照常现场执行");
            equal(new WorldEngine().openWorld(other.path).history().path("events").size(), 7, "另一世界独立保存七事件");
        });

        run("状态行动已提交而反馈失败时，仅恢复反馈不重做身份或登记", () -> {
            Fixture f = preparedClerk(); Map<Stage, AtomicInteger> calls = new EnumMap<>(Stage.class);
            ExperienceEngine engine = new ExperienceEngine(); for (Stage stage : Stage.values()) calls.put(stage, new AtomicInteger());
            for (ExperienceComponent base : BuiltinExperienceComponents.all()) engine.register(new ExperienceComponent() {
                public String id() { return "identity-" + base.stage().key(); } public String version() { return "1"; } public Stage stage() { return base.stage(); }
                public ObjectNode run(Context context) { int count = calls.get(stage()).incrementAndGet(); if (stage() == Stage.FEEDBACK && count == 1) throw new IllegalStateException("隔离反馈故障"); return base.run(context); }
            });
            ObjectNode request = action(f, "identity-feedback-recovery", "clerk").put("intent", "按作者明确给定的状态候选校正副本");
            ObjectNode scenario = request.putObject("scenario"); scenario.putArray("requiredEvidence"); scenario.putArray("remainingGaps");
            ObjectNode candidate = scenario.putArray("candidates").addObject().put("id", "state-response").put("module", "message-state-response").put("moduleVersion", "1").put("description", "候选模块自己按本人规则选择，仍经正式资格裁决").put("priority", 1);
            candidate.putObject("arguments").set("message", read("clerk-response.json").path("message").deepCopy());
            candidate.putArray("evidence").addObject().put("path", "/self/id").put("label", "核验主体").put("equals", "clerk");
            ArrayNode components = request.putArray("components"); for (Stage stage : Stage.values()) components.addObject().put("stage", stage.key()).put("id", "identity-" + stage.key()).put("version", "1");
            Path record = next("feedback"); rejects(() -> engine.run(f.world, request, record)); JsonNode failed = JSON.readTree(record.toFile());
            equal(failed.path("status").asText(), "feedbackFailed", "真实反馈失败保留记录"); equal(revision(f), 4, "状态行动已经正式提交");
            equal(known(f.world.view("keeper"), "registry-copy-code").path("source").asText(), "clerk", "分享后果仍保留");
            byte[] committed = Files.readAllBytes(f.path); ObjectNode recovered = engine.run(new WorldEngine().openWorld(f.path), request, record);
            equal(recovered.path("status").asText(), "completed", "同记录可恢复"); equal(recovered.path("receipt"), failed.path("receipt"), "同一权威回执");
            for (Stage stage : Stage.values()) equal(calls.get(stage).get(), stage == Stage.FEEDBACK ? 2 : 1, "只补反馈：" + stage);
            check(Arrays.equals(committed, Files.readAllBytes(f.path)), "恢复不重写世界"); noDelivery(f);
        });

        run("跨业务社区修缮工坊：同一模块依消息开放设备，不批准公众使用", () -> {
            ObjectNode source = read("workshop-seed.json"), original = source.deepCopy();
            workshopDirectory = directory.resolve("workshop"); Files.createDirectories(workshopDirectory);
            write(workshopDirectory.resolve("seed.json"), source);
            WorldStore world = new WorldEngine().openWorld(workshopDirectory.resolve("world.json")); world.initialize(source);
            Fixture f = new Fixture(world.path(), world, source.path("worldId").asText()); JsonNode initial = world.view("@author").deepCopy();
            ObjectNode response = read("workshop-response.json").put("expectedRevision", revision(f));
            unchangedReject(f, () -> world.step("message-state-response", response));
            check(!hasKnown(world.view("steward"), "repair-record"), "世界有修缮记录不等于管理者已收到消息");
            check(!hasKnown(world.view("volunteer"), "equipment-available"), "修缮者起点不知道设备是否开放");
            ObjectNode share = JSON.createObjectNode().put("type", "knowledge.share").put("from", "volunteer").put("to", "steward").put("factId", "repair-record");
            ObjectNode message = proposal(f, "volunteer-shares-repair-record", "volunteer", share).put("reason", "修缮者主动分享本人已有的修缮记录；此前修缮由作者设定，不是本次引擎模拟结果");
            write(workshopDirectory.resolve("repair-message.json"), message); JsonNode first = world.apply(message);
            equal(first.path("revision").asInt(), 1, "主动分享是第一事件");
            JsonNode received = known(world.view("steward"), "repair-record");
            equal(received.path("value").asText(), "repaired", "管理者收到明确修缮消息"); equal(received.path("source").asText(), "volunteer", "实际发言者来源");
            equal(received.path("revision").asInt(), 1, "消息精确接收版本"); equal(received.path("asOfYear").asInt(), 100, "原资料年份保留，不升级成现场核实");
            response.put("expectedRevision", revision(f)); write(workshopDirectory.resolve("response.json"), response);
            JsonNode receipt = world.step("message-state-response", "1", response);
            equal(receipt.path("revision").asInt(), 2, "同一个版本1模块回应第二事件");
            equal(fact(world.view("@author"), "equipment-available").path("value").asBoolean(), true, "设备可用性实际开放");
            equal(fact(world.view("@author"), "inspection-state").path("value").asText(), "passed", "关联检查状态同笔完成");
            equal(known(world.view("steward"), "equipment-available").path("source").asText(), "@world", "决定后按工坊现场观察");
            equal(known(world.view("steward"), "inspection-state").path("source").asText(), "@world", "关联目标也现场复核");
            JsonNode available = known(world.view("volunteer"), "equipment-available");
            equal(available.path("value").asBoolean(), true, "修缮者得到明确后果消息"); equal(available.path("source").asText(), "steward", "开放消息来自管理者");
            equal(available.path("revision").asInt(), 2, "来自正式回应事件"); check(!hasKnown(world.view("volunteer"), "inspection-state"), "未声明分享的关联状态不自动获知");
            equal(fact(world.view("@author"), "spare-planks").path("value").asInt(), 4, "不编造资源消耗");
            equal(fact(world.view("@author"), "public-use-permission").path("value").asBoolean(), false, "另一个未满足事项仍待处理，设备可用不等于公众获准使用");
            equal(fact(world.viewAt("@author", 0), "equipment-available").path("value").asBoolean(), false, "旧历史仍为不可用");
            check(!hasKnown(world.viewAt("steward", 0), "repair-record"), "历史人物视图未回填收到的消息");
            equal(world.history().path("events").size(), 2, "只有分享和管理者回应两次事件");
            JsonNode event = world.history().path("events").get(1); equal(event.path("submission").path("module").asText(), "message-state-response", "使用同一个模块，无场景专用代码");
            equal(event.path("submission").path("moduleVersion").asText(), "1", "版本固定为同一实现");
            equal(event.path("proposal").path("effects").size(), 5, "两项变化、两项观察与一项分享，未夹入使用许可或资源效果");
            byte[] bytes = Files.readAllBytes(f.path); WorldStore reopened = new WorldEngine().openWorld(f.path);
            equal(reopened.apply(message), first, "重复分享返回原回执"); equal(reopened.step("message-state-response", response), receipt, "重复回应返回原版本1回执");
            equal(reopened.view("@author"), world.view("@author"), "重开恢复关联状态与独立认识"); check(Arrays.equals(bytes, Files.readAllBytes(f.path)), "重复恢复不追加或改写事件");
            stable(initial, world.view("@author")); privacy(f, world.view("steward")); equal(source, original, "不改调用者种子");
            write(workshopDirectory.resolve("history.json"), world.history()); write(workshopDirectory.resolve("final-author.json"), world.view("@author"));
            write(workshopDirectory.resolve("response-receipt.json"), receipt);
            ObjectNode disqualified = source.deepCopy(); fact(disqualified, "steward-role").put("value", "volunteer"); Fixture bad = fresh(disqualified);
            ObjectNode wrongMessage = message.deepCopy().put("expectedRevision", 0); bad.world.apply(wrongMessage);
            JsonNode candidate = bad.world.propose("message-state-response", response);
            equal(candidate.path("status").asText(), "prepared", "本人消息足够产生候选，但不能从静态名单推出真实管理资格");
            unchangedReject(bad, () -> bad.world.step("message-state-response", response)); unchangedReject(bad, () -> new WorldStore(bad.path).commitPrepared(candidate));
            equal(fact(bad.world.view("@author"), "equipment-available").path("value").asBoolean(), false, "角色不符不开放设备");
            equal(fact(bad.world.view("@author"), "inspection-state").path("value").asText(), "pending", "角色不符时关联状态也整体保持");
        });

        int passed = (int) RESULTS.stream().filter(row -> row.path("passed").asBoolean()).count();
        ObjectNode report = JSON.createObjectNode().put("schemaVersion", 1).put("kind", "identity-check").put("checks", RESULTS.size()).put("passed", passed).put("failed", RESULTS.size() - passed);
        report.set("results", JSON.valueToTree(RESULTS)); report.put("isolatedTestDirectory", directory.toString()).put("examplesDirectory", examples.toAbsolutePath().toString());
        if (positiveDirectory != null) report.put("positiveReplayDirectory", positiveDirectory.toString());
        if (workshopDirectory != null) report.put("workshopReplayDirectory", workshopDirectory.toString());
        report.put("evidenceLimit", "本地结构化作者前提、角色资格守卫、消息与声明规则导致实际状态后果、全包提交及恢复；未接AI，不证明自然语言解析、心理或权力分配自主推导、动态法则生成、货物成功交付或小说质量。同一时间线只追加，不修改过去。");
        if (args.length > 0) write(Path.of(args[0]).toAbsolutePath(), report);
        System.out.println(JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report)); if (passed != RESULTS.size()) System.exit(1);
    }

    private record Fixture(Path path, WorldStore world, String worldId) {}
    private static Fixture fresh() throws Exception { return fresh(seed()); }
    private static Fixture fresh(ObjectNode seed) throws Exception { Path path = next("world"); WorldStore world = new WorldEngine().openWorld(path); world.initialize(seed); return new Fixture(path, world, seed.path("worldId").asText()); }
    private static Fixture preparedClerk() throws Exception { return preparedClerk(seed()); }
    private static Fixture preparedClerk(ObjectNode seed) throws Exception { Fixture f = fresh(seed); f.world.apply(input(f, "appoint.json")); f.world.step("travel", input(f, "keeper-travel.json")); f.world.apply(input(f, "keeper-share-slip.json")); return f; }
    private static Fixture correctedClerk() throws Exception { Fixture f = preparedClerk(); f.world.step("message-state-response", input(f, "clerk-response.json")); return f; }
    private static void backToWarehouse(Fixture f) throws Exception { f.world.step("travel", input(f, "clerk-return-warehouse.json")); f.world.step("travel", input(f, "keeper-return-warehouse.json")); }
    private static ObjectNode read(String file) throws Exception { return (ObjectNode) JSON.readTree(examples.resolve("identity").resolve(file).toFile()); }
    private static ObjectNode seed() throws Exception { return (ObjectNode) new WorldGenesis().build(read("genesis.json")).path("seed").deepCopy(); }
    private static Path next(String stem) { return directory.resolve(stem + "-" + ++number + ".json"); }
    private static int revision(Fixture f) throws Exception { return f.world.inspect().path("revision").asInt(); }
    private static ObjectNode action(Fixture f, String id, String actor) throws Exception { return JSON.createObjectNode().put("schemaVersion", 1).put("worldId", f.worldId).put("requestId", id).put("expectedRevision", revision(f)).put("actor", actor); }
    private static ObjectNode input(Fixture f, String file) throws Exception { return read(file).put("worldId", f.worldId).put("expectedRevision", revision(f)); }
    private static ObjectNode proposal(Fixture f, String id, String actor, ObjectNode... effects) throws Exception { ObjectNode result = action(f, id, actor).put("reason", "隔离验收：资格来自作者明确声明的世界前提与当前角色，不由文字说明代替"); ArrayNode rows = result.putArray("effects"); for (ObjectNode effect : effects) rows.add(effect); return result; }
    private static ObjectNode change(String factId, Object oldValue, Object newValue) { ObjectNode node = JSON.createObjectNode().put("type", "fact.change").put("factId", factId); node.set("expectedValue", JSON.valueToTree(oldValue)); node.set("value", JSON.valueToTree(newValue)); return node; }
    private static ObjectNode observe(String person, String factId) { return JSON.createObjectNode().put("type", "knowledge.learn").put("personId", person).put("factId", factId).put("sourcePersonId", "@world"); }
    private static ObjectNode condition(String factId, Object value) { ObjectNode row = JSON.createObjectNode().put("factId", factId); row.set("equals", JSON.valueToTree(value)); return row; }
    private static ObjectNode testFact(String id, String subject, String predicate, Object value) { ObjectNode fact = JSON.createObjectNode().put("id", id).put("subject", subject).put("predicate", predicate).put("mode", "fixed"); fact.set("value", JSON.valueToTree(value)); fact.putArray("knownBy"); return fact; }
    private static ActionModule uncheckedCopy() { return new ActionModule() {
        public String id() { return "unchecked-copy"; }
        public JsonNode propose(JsonNode view, JsonNode input) { check(!view.has("facts") && !view.has("laws"), "扩展行为也只持本人视图"); ObjectNode result = JSON.createObjectNode(); for (String field : List.of("schemaVersion", "worldId", "requestId", "expectedRevision", "actor")) result.set(field, input.get(field).deepCopy()); result.put("reason", "反例：扩展行为不做资格判断，共同内核仍必须拒绝"); result.putArray("effects").add(change("registry-copy-code", "H-16", "H-17")); return result; }
    }; }
    private static JsonNode by(JsonNode rows, String key, String value) { for (JsonNode row : rows) if (row.path(key).asText().equals(value)) return row; throw new AssertionError("缺少 " + key + ":" + value); }
    private static ObjectNode fact(JsonNode view, String id) { return (ObjectNode) by(view.path("facts"), "id", id); }
    private static ObjectNode law(JsonNode seed, String id) { return (ObjectNode) by(seed.path("laws"), "id", id); }
    private static ObjectNode policy(JsonNode seed, String actor) { return (ObjectNode) by(seed.path("characters"), "id", actor).path("privateProfile").path("responsePolicy"); }
    private static JsonNode known(JsonNode view, String id) { return by(view.path("knowledge"), "factId", id); }
    private static JsonNode relation(JsonNode view, String other) { return by(view.path("relationships"), "to", other); }
    private static boolean hasKnown(JsonNode view, String id) { for (JsonNode row : view.path("knowledge")) if (row.path("factId").asText().equals(id)) return true; return false; }
    private static boolean contains(JsonNode rows, String text) { for (JsonNode row : rows) if (row.asText().equals(text)) return true; return false; }
    private static void removeText(ArrayNode rows, String value) { for (int i = rows.size() - 1; i >= 0; i--) if (rows.get(i).asText().equals(value)) rows.remove(i); }
    private static void removeBy(ArrayNode rows, String key, String value) { for (int i = rows.size() - 1; i >= 0; i--) if (rows.get(i).path(key).asText().equals(value)) rows.remove(i); }
    private static JsonNode remap(JsonNode value, Map<String, String> names) { if (value.isTextual()) return JSON.valueToTree(names.getOrDefault(value.asText(), value.asText())); if (value.isArray()) { ArrayNode result = JSON.createArrayNode(); for (JsonNode row : value) result.add(remap(row, names)); return result; } if (value.isObject()) { ObjectNode result = JSON.createObjectNode(); value.fields().forEachRemaining(entry -> result.set(entry.getKey(), remap(entry.getValue(), names))); return result; } return value.deepCopy(); }
    private static void noDelivery(Fixture f) throws Exception { JsonNode view = f.world.view("@author"); equal(fact(view, "cargo-location").path("value").asText(), "warehouse", "货物仍在货栈"); equal(fact(view, "iron-quantity").path("value").asInt(), 40, "没有消耗或搬运铁料"); equal(fact(view, "cargo-pickup-permission").path("value").asBoolean(), false, "未替货主放行"); equal(fact(view, "charcoal-quantity").path("value").asInt(), 8, "不伪造生产消耗"); }
    private static void stable(JsonNode before, JsonNode after) { equal(before.path("places"), after.path("places"), "地理不变"); equal(before.path("routes"), after.path("routes"), "已有路线不变"); for (JsonNode person : before.path("characters")) { JsonNode now = by(after.path("characters"), "id", person.path("id").asText()); equal(now.path("name"), person.path("name"), "同一人物姓名保持"); equal(now.path("privateProfile"), person.path("privateProfile"), "不暗改本人目标或策略"); } for (JsonNode fact : before.path("facts")) if (fact.path("mode").asText().equals("fixed")) equal(fact(after, fact.path("id").asText()).path("value"), fact.path("value"), "固定事实保持"); }
    private static void privacy(Fixture f, JsonNode view) throws Exception { check(!view.has("facts") && !view.has("laws") && !view.has("entities") && !view.has("characters"), "人物不持作者目录"); for (JsonNode person : view.path("nearby")) check(!person.has("privateProfile"), "他人策略未泄露"); check(!hasKnown(view, "registry-copy-origin"), "作者幕后信息仍未知"); equal(f.world.view("@reader").path("readerKnowledge").size(), 0, "读者不自动获知人物或作者信息"); }
    private static void unchangedReject(Fixture f, Checked action) throws Exception { byte[] bytes = Files.readAllBytes(f.path); JsonNode view = f.world.view("@author").deepCopy(), history = f.world.history().deepCopy(); rejects(action); check(Arrays.equals(bytes, Files.readAllBytes(f.path)), "拒绝不改世界字节"); equal(f.world.view("@author"), view, "拒绝不改世界资料"); equal(f.world.history(), history, "拒绝不加事件"); }
    private static void applySaved(Fixture f, String file) throws Exception { ObjectNode request = input(f, file); write(positiveDirectory.resolve(file), request); write(positiveDirectory.resolve(file.replace(".json", "-receipt.json")), f.world.apply(request)); }
    private static void stepSaved(Fixture f, String module, String file) throws Exception { ObjectNode request = input(f, file); write(positiveDirectory.resolve(file), request); write(positiveDirectory.resolve(file.replace(".json", "-receipt.json")), f.world.step(module, "1", request)); }
    private static void write(Path path, JsonNode value) throws Exception { Files.createDirectories(path.toAbsolutePath().getParent()); JSON.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), value); }
    private static void rejects(Checked action) throws Exception { try { action.run(); } catch (IllegalArgumentException | IllegalStateException | java.io.IOException expected) { return; } throw new AssertionError("非法操作未拒绝"); }
    private static void run(String name, Checked action) { ObjectNode row = JSON.createObjectNode().put("name", name); try { action.run(); row.put("passed", true); } catch (Exception | AssertionError failure) { row.put("passed", false).put("error", failure.getClass().getSimpleName() + ": " + failure.getMessage()); } RESULTS.add(row); }
    private static void equal(Object actual, Object expected, String why) { boolean same = actual instanceof JsonNode a && expected instanceof JsonNode b ? Protocol.canonical(a).equals(Protocol.canonical(b)) : java.util.Objects.equals(actual, expected); check(same, why + "；预期=" + expected + "；实际=" + actual); }
    private static void check(boolean value, String why) { if (!value) throw new AssertionError(why); }
    @FunctionalInterface private interface Checked { void run() throws Exception; }
}
