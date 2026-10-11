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

/** 共同年度时间、历史依据与事务边界；不推断未声明的人生经历。 */
public final class TimeCheck {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<ObjectNode> RESULTS = new ArrayList<>();
    private static Path testRoot, examples;
    private static int sequence;

    public static void main(String[] args) throws Exception {
        testRoot = Files.createTempDirectory("creative-world-time-check-");
        examples = args.length > 1 ? Path.of(args[1]) : Path.of("examples");
        if (args.length < 2 && !Files.exists(examples.resolve("time-town.json"))) examples = Path.of("creative-world-engine", "examples");

        run("共同世界初年及出生年产生三个具体年龄，远处人物也在同一时钟", () -> {
            Fixture f = town(); equal(year(f.store.inspect()), 100, "初始共同年份");
            age(f, "zhou-ning", 16); age(f, "xu-cheng", 16); age(f, "chen-chuan", 22);
            equal(f.store.view("chen-chuan").path("self").path("place").asText(), "remote-village", "第三人确实远离当前场景");
            for (String id : List.of("zhou-ning", "xu-cheng", "chen-chuan")) {
                JsonNode self = f.store.view(id).path("self"); equal(self.path("publicProfileAsOfYear").asInt(), 100, "公开资料冻结在初年");
                equal(self.path("privateProfileAsOfYear").asInt(), 100, "私密资料冻结在初年");
                equal(self.path("publicProfileStatus").asText(), "current", "初年公开资料状态");
                equal(self.path("privateProfileStatus").asText(), "current", "初年私密资料状态");
                check(!self.path("publicProfile").has("age") && !self.path("privateProfile").has("age"), "年龄不复制进人物描述");
            }
        });

        run("十年一起经过，年龄同时增长而名字地理资料和关系不自动改写", () -> {
            Fixture f = town(); JsonNode before = f.store.view("@author").deepCopy(); ObjectNode input = read("time-advance.json");
            f.store.apply(input); equal(year(f.store.inspect()), 110, "世界整体前进十年");
            age(f, "zhou-ning", 26); age(f, "xu-cheng", 26); age(f, "chen-chuan", 32);
            JsonNode after = f.store.view("@author"); equal(after.path("places"), before.path("places"), "没有声明的地理保持不变");
            equal(after.path("facts"), before.path("facts"), "没有声明的事实不随时间凭空变化");
            equal(after.path("relationships"), before.path("relationships"), "十年不自动变成同学、婚姻或和解");
            for (String id : List.of("zhou-ning", "xu-cheng", "chen-chuan")) {
                JsonNode old = byId(before.path("characters"), id), current = byId(after.path("characters"), id);
                equal(current.path("name"), old.path("name"), "名字不随时间改写"); equal(current.path("place"), old.path("place"), "位置不自动变化");
                equal(current.path("publicProfile"), old.path("publicProfile"), "公开资料未造出成长经历");
                equal(current.path("privateProfile"), old.path("privateProfile"), "私密资料未造出情感转变");
                equal(current.path("publicProfileStatus").asText(), "historical", "旧公开描述明确是历史资料");
                equal(current.path("privateProfileStatus").asText(), "historical", "旧私密描述明确是历史资料");
                equal(current.path("publicProfileAsOfYear").asInt(), 100, "冻结资料年份没有伪装成110");
            }
        });

        run("同一年可有多个版本，关系和信念记发生年而非版本号", () -> {
            Fixture f = town(); f.store.apply(read("time-advance.json")); f.store.apply(read("time-relations.json"));
            f.store.apply(proposal(f, "same-year-belief", "zhou-ning", JSON.createObjectNode().put("type", "belief.add").put("personId", "zhou-ning").put("text", "同学关系已经由作者声明")));
            equal(f.store.inspect().path("revision").asInt(), 3, "一年内有多个事件版本"); equal(year(f.store.inspect()), 110, "世界年份不按版本数增加");
            JsonNode relation = relation(f.store.view("zhou-ning").path("relationships"), "xu-cheng");
            equal(relation.path("value").asText(), "同学", "关系按明确声明变化"); equal(relation.path("occurredAtYear").asInt(), 110, "关系发生在共同当前年");
            equal(relation.path("revision").asInt(), 2, "关系版本与年份区分"); check(!relation.path("basis").asText().isBlank(), "关系变化有依据");
            JsonNode belief = f.store.view("zhou-ning").path("beliefs").get(0); equal(belief.path("occurredAtYear").asInt(), 110, "信念有发生年");
            equal(belief.path("revision").asInt(), 3, "信念有独立事件版本");
            for (JsonNode event : f.store.history().path("events")) equal(year(event), 110, "每个事件保存提交后的共同时钟");
        });

        run("关系改变后旧关系、依据与旧年份仍能通过历史版本回读", () -> {
            Fixture f = town(); JsonNode original = relation(f.store.view("zhou-ning").path("relationships"), "xu-cheng").deepCopy();
            equal(original.path("occurredAtYear").asInt(), 100, "初始关系属于初年");
            f.store.apply(read("time-advance.json")); f.store.apply(read("time-relations.json"));
            equal(relation(f.store.viewAt("zhou-ning", 0).path("relationships"), "xu-cheng"), original, "初始关系没有被覆盖");
            equal(relation(f.store.viewAt("zhou-ning", 1).path("relationships"), "xu-cheng"), original, "只推进时间不伪造关系变化");
            equal(year(f.store.viewAt("zhou-ning", 0)), 100, "旧版本仍是旧世界时间");
            equal(f.store.viewAt("zhou-ning", 0).path("self").path("age").asInt(), 16, "旧年龄不跟着当前年龄变");
            equal(relation(f.store.viewAt("zhou-ning", 2).path("relationships"), "xu-cheng").path("value").asText(), "同学", "新关系只在新版本出现");
        });

        run("未知出生年始终未知，描述中的年龄暗示不能代替出生依据", () -> {
            ObjectNode seed = townSeed(); ObjectNode person = person(seed, "zhou-ning"); person.remove("birthYear");
            ((ObjectNode) person.path("publicProfile")).put("appearance", "看上去像一个十六岁的少年"); Fixture f = fresh(seed);
            check(f.store.view("zhou-ning").path("self").path("age").isNull(), "缺出生年时年龄为null");
            f.store.apply(proposal(f, "unknown-birth-ten-years", "@author", advance(100, 110)));
            check(f.store.view("zhou-ning").path("self").path("age").isNull(), "经过十年也不编造出生年");
            check(f.store.viewAt("zhou-ning", 0).path("self").path("age").isNull(), "旧版本也保留未知");
            for (JsonNode nearby : f.store.view("xu-cheng").path("nearby")) if (nearby.path("id").asText().equals("zhou-ning")) check(nearby.path("age").isNull(), "旁人视图也不能猜年龄");
        });

        run("转述的获取年是当前年，证据年继承旧来源而非伪装成新观察", () -> {
            ObjectNode seed = goalSeed(); person(seed, "porter").put("place", "wharf"); Fixture f = fresh(seed);
            JsonNode initial = knowledge(f.store.view("boatman"), "cargo-location"); equal(initial.path("learnedAtYear").asInt(), 100, "初始获取年"); equal(initial.path("asOfYear").asInt(), 100, "初始证据年");
            f.store.apply(proposal(f, "rumor-time-forward", "@author", advance(100, 110)));
            f.store.step("ask", input(f, "ask-old-boatman", "apprentice").put("target", "boatman").put("factId", "cargo-location"));
            JsonNode heard = knowledge(f.store.view("apprentice"), "cargo-location");
            equal(heard.path("learnedAtYear").asInt(), 110, "后来才听到消息"); equal(heard.path("asOfYear").asInt(), 100, "旧证据仍来自100年");
            equal(heard.path("source").asText(), "boatman", "保留直接转述来源");
            f.store.step("ask", input(f, "retell-old-rumor", "porter").put("target", "apprentice").put("factId", "cargo-location"));
            JsonNode retold = knowledge(f.store.view("porter"), "cargo-location"); equal(retold.path("learnedAtYear").asInt(), 110, "二次转述取得于当前年");
            equal(retold.path("asOfYear").asInt(), 100, "二次转述不能把旧证据洗成新年"); equal(retold.path("source").asText(), "apprentice", "记录实际二次来源");
            equal(knowledge(f.store.view("boatman"), "cargo-location"), initial, "原来源的获取年和证据年未被他人询问修改");
        });

        run("现场观察在当前年取得当前证据，之前的转述快照留在历史中", () -> {
            Fixture f = fresh(goalSeed()); f.store.apply(proposal(f, "observation-time-forward", "@author", advance(100, 110)));
            f.store.step("ask", input(f, "old-position-before-visit", "apprentice").put("target", "boatman").put("factId", "cargo-location"));
            JsonNode old = knowledge(f.store.view("apprentice"), "cargo-location").deepCopy();
            f.store.step("travel", input(f, "go-observe-current-year", "apprentice").put("to", "warehouse"));
            f.store.step("observe", input(f, "observe-current-year", "apprentice").put("factId", "cargo-location"));
            JsonNode current = knowledge(f.store.view("apprentice"), "cargo-location"); equal(current.path("learnedAtYear").asInt(), 110, "观察获取年");
            equal(current.path("asOfYear").asInt(), 110, "观察证据年"); equal(current.path("source").asText(), "@world", "观察来源");
            equal(knowledge(f.store.viewAt("apprentice", 2), "cargo-location"), old, "旧转述快照仍可回读");
        });

        run("状态事实只在真实变更时更新年份，单纯经过十年不刷新旧证据", () -> {
            Fixture f = fresh(goalSeed()); equal(fact((ObjectNode) f.store.view("@author"), "iron-quantity").path("updatedAtYear").asInt(), 100, "初始状态事实年");
            JsonNode fixed = fact((ObjectNode) f.store.view("@author"), "town-origin").deepCopy();
            f.store.apply(proposal(f, "facts-time-forward", "@author", advance(100, 110)));
            equal(fact((ObjectNode) f.store.view("@author"), "iron-quantity").path("updatedAtYear").asInt(), 100, "时间经过不冒充事实更新");
            f.store.apply(proposal(f, "actually-change-quantity", "@author", JSON.createObjectNode().put("type", "fact.change").put("factId", "iron-quantity").put("expectedValue", 40).put("value", 35)));
            equal(fact((ObjectNode) f.store.view("@author"), "iron-quantity").path("updatedAtYear").asInt(), 110, "真正状态变化自动使用当前年");
            equal(fact((ObjectNode) f.store.viewAt("@author", 1), "iron-quantity").path("value").asInt(), 40, "旧事实值仍保留");
            equal(fact((ObjectNode) f.store.view("@author"), "town-origin"), fixed, "固定地方来由没有变化");
        });

        run("时间与后续关系同事务时，后果使用推进后的共同年份", () -> {
            Fixture f = town(); ObjectNode relation = JSON.createObjectNode().put("type", "relationship.change").put("from", "zhou-ning").put("to", "xu-cheng")
                .put("expectedValue", "主人家的晚辈").put("value", "同学").put("basis", "作者在十年后明确设定两人同入学堂");
            JsonNode receipt = f.store.apply(proposal(f, "time-and-relation", "@author", advance(100, 110), relation));
            equal(receipt.path("revision").asInt(), 1, "整提案只追加一个版本"); equal(year(f.store.history().path("events").get(0)), 110, "事件保存提交后时钟");
            equal(relation(f.store.view("zhou-ning").path("relationships"), "xu-cheng").path("occurredAtYear").asInt(), 110, "后果不使用推进前的旧年份");
            age(f, "chen-chuan", 32);
        });

        run("重复原时间提案返回原回执，不受后来版本影响且不改文件", () -> {
            Fixture f = town(); ObjectNode original = read("time-advance.json"); JsonNode receipt = f.store.apply(original); f.store.apply(read("time-relations.json"));
            byte[] bytes = Files.readAllBytes(f.path); equal(f.store.apply(original.deepCopy()), receipt, "重放回原回执");
            check(Arrays.equals(bytes, Files.readAllBytes(f.path)), "重复提案不再次推进时间或落账");
            equal(receipt.size(), 5, "回执仍保持既有五字段合同"); equal(year(f.store.inspect()), 110, "没有重复推进十年");
        });

        run("重开存档与历史回放保留世界时钟、年龄和关系依据", () -> {
            Fixture f = town(); f.store.apply(read("time-advance.json")); f.store.apply(read("time-relations.json"));
            WorldStore reopened = new WorldStore(f.path); equal(reopened.inspect(), f.store.inspect(), "重开概况一致");
            equal(reopened.view("@author"), f.store.view("@author"), "重放所得全量状态一致");
            equal(year(reopened.history()), 110, "历史概况包含当前时钟");
            equal(year(reopened.viewAt("chen-chuan", 0)), 100, "旧版本保留旧年"); equal(reopened.viewAt("chen-chuan", 0).path("self").path("age").asInt(), 22, "旧远处人物年龄");
            equal(reopened.view("chen-chuan").path("self").path("age").asInt(), 32, "当前远处人物年龄");
            check(!relation(reopened.view("zhou-ning").path("relationships"), "xu-cheng").path("basis").asText().isBlank(), "关系依据重放后仍存在");
        });

        run("非法时钟、出生年及重复年龄资料在初始化前拒绝", () -> {
            for (String variation : List.of("unit", "negative-year", "fractional-year", "future-birth", "negative-birth", "public-age", "private-age", "public-birth", "private-birth", "derived-age", "derived-profile-year")) {
                ObjectNode seed = townSeed(); ObjectNode person = person(seed, "zhou-ning");
                switch (variation) {
                    case "unit" -> ((ObjectNode) seed.path("worldTime")).put("unit", "month");
                    case "negative-year" -> ((ObjectNode) seed.path("worldTime")).put("year", -1);
                    case "fractional-year" -> ((ObjectNode) seed.path("worldTime")).put("year", 100.5);
                    case "future-birth" -> person.put("birthYear", 101);
                    case "negative-birth" -> person.put("birthYear", -1);
                    case "public-age" -> ((ObjectNode) person.path("publicProfile")).put("age", 16);
                    case "private-age" -> ((ObjectNode) person.path("privateProfile")).put("age", 16);
                    case "public-birth" -> ((ObjectNode) person.path("publicProfile")).put("birthYear", 84);
                    case "private-birth" -> ((ObjectNode) person.path("privateProfile")).put("birthYear", 84);
                    case "derived-age" -> person.put("age", 16);
                    case "derived-profile-year" -> person.put("publicProfileAsOfYear", 100);
                    default -> throw new AssertionError("没有测试变体");
                }
                Path path = next("invalid-initial-time"); WorldStore store = new WorldStore(path); rejects(() -> store.initialize(seed));
                check(!Files.exists(path), "非法起点不创建正式世界：" + variation);
            }
        });

        run("倒退、旧年份、非作者、多次推进或推进不在首位均拒绝且字节不变", () -> {
            Fixture f = town(); List<ObjectNode> invalid = new ArrayList<>();
            invalid.add(proposal(f, "backwards", "@author", advance(100, 90))); invalid.add(proposal(f, "no-time-change", "@author", advance(100, 100)));
            invalid.add(proposal(f, "old-clock", "@author", advance(99, 110))); invalid.add(proposal(f, "actor-clock", "zhou-ning", advance(100, 110)));
            invalid.add(proposal(f, "twice-clock", "@author", advance(100, 110), advance(110, 120)));
            invalid.add(proposal(f, "clock-not-first", "@author", JSON.createObjectNode().put("type", "belief.add").put("personId", "zhou-ning").put("text", "时间推进前插入的效果"), advance(100, 110)));
            for (ObjectNode input : invalid) rejectUnchanged(f, () -> f.store.apply(input));
            equal(year(f.store.inspect()), 100, "所有非法提案后仍为初年"); equal(f.store.history().path("events").size(), 0, "没有部分落账");
        });

        run("时间加非法后果整体回滚，时钟副本不能先泄漏到正式世界", () -> {
            Fixture f = fresh(goalSeed());
            ObjectNode violateLaw = JSON.createObjectNode().put("type", "fact.change").put("factId", "iron-quantity").put("expectedValue", 40).put("value", -1);
            rejectUnchanged(f, () -> f.store.apply(proposal(f, "clock-and-illegal-stock", "@author", advance(100, 110), violateLaw)));
            ObjectNode changeFixed = JSON.createObjectNode().put("type", "fact.change").put("factId", "town-origin").set("expectedValue", fact((ObjectNode) f.store.view("@author"), "town-origin").get("value").deepCopy());
            changeFixed.put("value", "凭空改写地理历史"); rejectUnchanged(f, () -> f.store.apply(proposal(f, "clock-and-fixed-rewrite", "@author", advance(100, 110), changeFixed)));
            equal(year(f.store.inspect()), 100, "非法后果连同十年推进回滚"); equal(fact((ObjectNode) f.store.view("@author"), "iron-quantity").path("updatedAtYear").asInt(), 100, "事实时间没有部分刷新");
        });

        run("有时钟关系变化必须有依据，不能用时间自动替关系解释", () -> {
            Fixture f = town(); ObjectNode change = JSON.createObjectNode().put("type", "relationship.change").put("from", "zhou-ning").put("to", "xu-cheng")
                .put("expectedValue", "主人家的晚辈").put("value", "同学");
            rejectUnchanged(f, () -> f.store.apply(proposal(f, "relation-without-basis", "@author", change)));
            change.put("basis", " "); rejectUnchanged(f, () -> f.store.apply(proposal(f, "relation-empty-basis", "@author", change)));
        });

        run("取得时钟或人物投影后修改副本不会污染世界，其他人的私密年份不泄漏", () -> {
            Fixture f = town(); byte[] bytes = Files.readAllBytes(f.path);
            ObjectNode inspect = (ObjectNode) f.store.inspect(), author = (ObjectNode) f.store.view("@author"), actor = (ObjectNode) f.store.view("zhou-ning"), history = (ObjectNode) f.store.history();
            for (ObjectNode value : List.of(inspect, author, actor, history)) ((ObjectNode) value.path("worldTime")).put("year", 999);
            ((ObjectNode) actor.path("self")).put("age", 500);
            equal(year(f.store.inspect()), 100, "修改投影时钟不修改权威时钟"); age(f, "zhou-ning", 16);
            check(Arrays.equals(bytes, Files.readAllBytes(f.path)), "修改读取副本不写文件");
            for (JsonNode nearby : f.store.view("zhou-ning").path("nearby")) {
                check(!nearby.has("privateProfile") && !nearby.has("privateProfileAsOfYear") && !nearby.has("privateProfileStatus"), "其他人物私密资料及年份不得公开");
                check(nearby.has("publicProfileAsOfYear") && nearby.has("publicProfileStatus"), "公开资料保留自己的时间标签");
            }
        });

        run("篡改历史事件时间会在重放时拒绝，旧无时钟历史也不能附伪时钟", () -> {
            Fixture f = town(); f.store.apply(read("time-advance.json")); ObjectNode saved = (ObjectNode) JSON.readTree(f.path.toFile());
            for (String variation : List.of("missing", "wrong-year", "wrong-unit", "unknown-field")) {
                ObjectNode corrupt = saved.deepCopy(); ObjectNode event = (ObjectNode) corrupt.path("events").get(0);
                if (variation.equals("missing")) event.remove("worldTime");
                if (variation.equals("wrong-year")) ((ObjectNode) event.path("worldTime")).put("year", 100);
                if (variation.equals("wrong-unit")) ((ObjectNode) event.path("worldTime")).put("unit", "month");
                if (variation.equals("unknown-field")) ((ObjectNode) event.path("worldTime")).put("guessed", true);
                Path path = next("bad-history-clock"); JSON.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), corrupt); byte[] before = Files.readAllBytes(path);
                rejects(() -> new WorldStore(path).inspect()); check(Arrays.equals(before, Files.readAllBytes(path)), "重放拒绝不修写不可信历史");
            }
            ObjectNode old = (ObjectNode) new WorldGenesis().build(read("goal-river-town.json")).path("seed"); Fixture untimed = fresh(old);
            untimed.store.step("ask", input(untimed, "old-time-free-event", "apprentice").put("target", "boatman").put("factId", "cargo-location"));
            ObjectNode corruptedOld = (ObjectNode) JSON.readTree(untimed.path.toFile()); ((ObjectNode) corruptedOld.path("events").get(0)).putObject("worldTime").put("unit", "year").put("year", 100);
            Path path = next("clock-in-old-history"); JSON.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), corruptedOld); rejects(() -> new WorldStore(path).inspect());
        });

        run("旧无时钟世界保持原形状且拒绝新增出生年或时间推进", () -> {
            ObjectNode seed = (ObjectNode) new WorldGenesis().build(read("goal-river-town.json")).path("seed"); Fixture f = fresh(seed);
            check(!f.store.inspect().has("worldTime") && !f.store.view("apprentice").has("worldTime"), "不创建默认时钟");
            JsonNode self = f.store.view("apprentice").path("self"); check(!self.has("age") && !self.has("publicProfileStatus"), "不改变旧人物投影合同");
            equal(self.path("publicProfile").path("age").asInt(), 19, "旧任意公开资料保持兼容");
            rejectUnchanged(f, () -> f.store.apply(proposal(f, "clock-in-untimed", "@author", advance(100, 110))));
            ObjectNode child = JSON.createObjectNode().put("id", "new-child").put("name", "新人物").put("place", "wharf").put("birthYear", 100); child.putObject("publicProfile"); child.putObject("privateProfile");
            ObjectNode effect = JSON.createObjectNode().put("type", "person.add").set("person", child); rejectUnchanged(f, () -> f.store.apply(proposal(f, "birth-in-untimed", "@author", effect)));
            f.store.step("ask", input(f, "old-ask-still-works", "apprentice").put("target", "boatman").put("factId", "cargo-location"));
            JsonNode known = knowledge(f.store.view("apprentice"), "cargo-location"); check(!known.has("learnedAtYear") && !known.has("asOfYear"), "旧认识形状不插入时间字段");
        });

        run("新人物和新事实按创建当年定资料与证据时间，未知人生不补写", () -> {
            Fixture f = town(); f.store.apply(read("time-advance.json"));
            ObjectNode child = JSON.createObjectNode().put("id", "new-student").put("name", "小新").put("place", "academy").put("birthYear", 100);
            child.putObject("publicProfile").put("appearance", "背着旧布包"); child.putObject("privateProfile").put("intent", "先问清上课地点");
            ObjectNode add = JSON.createObjectNode().put("type", "person.add").set("person", child);
            ObjectNode value = JSON.createObjectNode().put("id", "classroom-open").put("subject", "academy").put("predicate", "room-open").put("value", true).put("mode", "state"); value.putArray("knownBy").add("new-student");
            ObjectNode define = JSON.createObjectNode().put("type", "fact.define").set("fact", value);
            f.store.apply(proposal(f, "new-timed-materials", "@author", add, define)); age(f, "new-student", 10);
            JsonNode self = f.store.view("new-student").path("self"); equal(self.path("publicProfileAsOfYear").asInt(), 110, "新人物公开资料创建年");
            equal(self.path("privateProfileStatus").asText(), "current", "新资料属于当前年");
            JsonNode known = knowledge(f.store.view("new-student"), "classroom-open"); equal(known.path("learnedAtYear").asInt(), 110, "新事实取得年"); equal(known.path("asOfYear").asInt(), 110, "新事实证据年");
        });

        run("旧现场观察不能满足十年后新查证，连续过程及旧反馈保留各自时钟", () -> {
            Fixture f = fresh(goalSeed()); ObjectNode original = goalRequest(f, "timed-first-goal"); Path oldDirectory = testRoot.resolve("timed-goal-100");
            ObjectNode completed = GoalJourney.run(f.store, original, oldDirectory, 8); equal(completed.path("status").asText(), "satisfied", "100年完成一次现场查证");
            equal(f.store.inspect().path("revision").asInt(), 3, "首次询问、移动、观察三事件");
            JsonNode oldObservation = knowledge(f.store.view("apprentice"), "cargo-location").deepCopy(); equal(oldObservation.path("asOfYear").asInt(), 100, "旧观察证据年");
            Path oldFeedbackPath = oldDirectory.resolve("step-003.json"); byte[] oldFeedback = Files.readAllBytes(oldFeedbackPath);
            f.store.apply(proposal(f, "time-after-old-observation", "@author", advance(100, 110)));
            equal(knowledge(f.store.view("apprentice"), "cargo-location"), oldObservation, "时间经过不刷新旧现场证据");
            ObjectNode next = goalRequest(f, "timed-new-goal"); Path nextDirectory = testRoot.resolve("timed-goal-110"); ObjectNode renewed = GoalJourney.run(f.store, next, nextDirectory, 8);
            equal(renewed.path("status").asText(), "satisfied", "新查证只有重新观察才满足"); equal(f.store.inspect().path("revision").asInt(), 5, "十年后只增加真实时间事件和一次新观察");
            JsonNode nextRecord = JSON.readTree(nextDirectory.resolve("step-001.json").toFile()); equal(action(nextRecord).path("module").asText(), "observe", "旧 @world来源不能提前满足新年目标");
            equal(year(nextRecord.path("initialView")), 110, "新过程起始时钟"); equal(year(nextRecord.path("finalView")), 110, "新反馈时钟");
            equal(knowledge(f.store.view("apprentice"), "cargo-location").path("asOfYear").asInt(), 110, "新观察证据年");
            byte[] world = Files.readAllBytes(f.path); equal(GoalJourney.run(f.store, original, oldDirectory, 8), completed, "旧过程按历史时钟重读");
            check(Arrays.equals(world, Files.readAllBytes(f.path)) && Arrays.equals(oldFeedback, Files.readAllBytes(oldFeedbackPath)), "历史恢复不抹旧反馈或重做动作");
            JsonNode oldRecord = JSON.readTree(oldFeedbackPath.toFile()); equal(year(oldRecord.path("finalView")), 100, "旧反馈仍在100年");
        });

        run("同年事实变化后新核实目标仍需重观察，不能只凭年份相同满足", () -> {
            Fixture f = fresh(goalSeed()); Path firstDirectory = testRoot.resolve("same-year-first-observation");
            GoalJourney.run(f.store, goalRequest(f, "same-year-first-goal"), firstDirectory, 8);
            JsonNode original = knowledge(f.store.view("apprentice"), "cargo-location").deepCopy();
            equal(original.path("source").asText(), "@world", "先有真实现场认识"); equal(original.path("revision").asInt(), 3, "原观察版本");
            f.store.apply(proposal(f, "same-year-cargo-change", "@author", JSON.createObjectNode().put("type", "fact.change")
                .put("factId", "cargo-location").put("expectedValue", "warehouse").put("value", "forge")));
            equal(year(f.store.inspect()), 100, "事实变化发生在同一年");
            equal(knowledge(f.store.view("apprentice"), "cargo-location"), original, "真实变化不偷偷更新人物旧认识");
            Path renewedDirectory = testRoot.resolve("same-year-renewed-observation");
            ObjectNode result = GoalJourney.run(f.store, goalRequest(f, "same-year-renewed-goal"), renewedDirectory, 8);
            equal(result.path("status").asText(), "satisfied", "重新核实后正常满足");
            JsonNode step = JSON.readTree(renewedDirectory.resolve("step-001.json").toFile());
            equal(step.path("status").asText(), "completed", "第一步不是把旧观察直接当作已满足");
            equal(action(step).path("module").asText(), "observe", "依据既有可观察地点声明重新观察");
            equal(f.store.inspect().path("revision").asInt(), 5, "同年变更后只新增一次实际观察事件");
            JsonNode current = knowledge(f.store.view("apprentice"), "cargo-location");
            equal(current.path("value").asText(), "forge", "观察取得已变更的真实值"); equal(current.path("asOfYear").asInt(), 100, "新旧观察可以处在同一年");
            equal(current.path("revision").asInt(), 5, "用新的观察版本区分同年新证据");
            equal(knowledge(f.store.viewAt("apprentice", 3), "cargo-location"), original, "旧同年观察仍保存在其历史版本");
        });

        run("构造扩展不能贡献第二份共同时间，即使时钟值相同", () -> {
            ObjectNode blueprint = read("time-town.json"), original = blueprint.deepCopy();
            for (int suppliedYear : List.of(100, 110)) {
                WorldGenesis genesis = new WorldGenesis().register(new WorldConstructor() {
                    public String id() { return "another-clock"; }
                    public Set<String> dependencies() { return Set.of("world"); }
                    public ObjectNode construct(Context context) {
                        ObjectNode output = JSON.createObjectNode();
                        output.putObject("worldTime").put("unit", "year").put("year", suppliedYear);
                        return output;
                    }
                });
                rejects(() -> genesis.build(blueprint));
                equal(blueprint, original, "重复贡献拒绝后原蓝图不变");
            }
        });

        long passed = RESULTS.stream().filter(row -> row.path("passed").asBoolean()).count(); ObjectNode report = JSON.createObjectNode();
        report.put("checks", RESULTS.size()).put("passed", passed).put("failed", RESULTS.size() - passed).set("results", JSON.valueToTree(RESULTS));
        report.put("actualModelCalled", false).put("formalServiceUsed", false).put("proseQualityEvaluated", false);
        report.put("evidenceLimit", "仅验证共同年度时钟、派生年龄、历史证据年份和实际事务；不自动推断毕业、婚姻、情感、物理变化或小说质量。");
        report.put("isolatedTestDirectory", testRoot.toString()).put("examplesDirectory", examples.toAbsolutePath().toString());
        if (args.length > 0) { Path output = Path.of(args[0]).toAbsolutePath(); Files.createDirectories(output.getParent()); JSON.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report); }
        System.out.println(JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report)); if (passed != RESULTS.size()) System.exit(1);
    }

    private record Fixture(Path path, WorldStore store) {}
    private static Path next(String kind) { return testRoot.resolve(kind + "-" + ++sequence + ".json"); }
    private static ObjectNode read(String name) throws Exception { return (ObjectNode) JSON.readTree(examples.resolve(name).toFile()); }
    private static ObjectNode townSeed() throws Exception { return (ObjectNode) new WorldGenesis().build(read("time-town.json")).path("seed"); }
    private static Fixture town() throws Exception { return fresh(townSeed()); }
    private static Fixture fresh(ObjectNode seed) throws Exception { Path path = next("world"); WorldStore store = new WorldStore(path); store.initialize(seed); return new Fixture(path, store); }
    private static ObjectNode goalSeed() throws Exception {
        ObjectNode seed = (ObjectNode) new WorldGenesis().build(read("goal-river-town.json")).path("seed"); seed.putObject("worldTime").put("unit", "year").put("year", 100);
        for (JsonNode person : seed.path("characters")) for (String profile : List.of("publicProfile", "privateProfile")) { ((ObjectNode) person.path(profile)).remove("age"); ((ObjectNode) person.path(profile)).remove("birthYear"); }
        return seed;
    }
    private static int year(JsonNode value) { check(value.path("worldTime").path("unit").asText().equals("year"), "有明确年度时钟"); return value.path("worldTime").path("year").asInt(); }
    private static void age(Fixture f, String id, int expected) throws Exception { JsonNode self = f.store.view(id).path("self"); check(self.path("age").isIntegralNumber(), "已知出生年的年龄必须是整数"); equal(self.path("age").asInt(), expected, "人物年龄：" + id); }
    private static ObjectNode byId(JsonNode values, String id) { for (JsonNode row : values) if (row.path("id").asText().equals(id)) return (ObjectNode) row; throw new AssertionError("未找到：" + id); }
    private static ObjectNode person(ObjectNode seed, String id) { return byId(seed.path("characters"), id); }
    private static ObjectNode fact(ObjectNode seed, String id) { return byId(seed.path("facts"), id); }
    private static JsonNode relation(JsonNode values, String target) { for (JsonNode value : values) if (value.path("to").asText().equals(target)) return value; throw new AssertionError("未找到关系：" + target); }
    private static JsonNode knowledge(JsonNode view, String factId) { for (JsonNode value : view.path("knowledge")) if (value.path("factId").asText().equals(factId)) return value; throw new AssertionError("未找到认识：" + factId); }
    private static ObjectNode input(Fixture f, String id, String actor) throws Exception { return JSON.createObjectNode().put("schemaVersion", 1).put("worldId", f.store.inspect().path("worldId").asText()).put("requestId", id).put("expectedRevision", f.store.inspect().path("revision").asInt()).put("actor", actor); }
    private static ObjectNode proposal(Fixture f, String id, String actor, ObjectNode... effects) throws Exception { ObjectNode result = input(f, id, actor).put("reason", "有明确依据的时间验收动作"); ArrayNode rows = result.putArray("effects"); for (ObjectNode effect : effects) rows.add(effect); return result; }
    private static ObjectNode advance(int expected, int to) { return JSON.createObjectNode().put("type", "time.advance").put("expectedYear", expected).put("toYear", to); }
    private static ObjectNode goalRequest(Fixture f, String id) throws Exception { ObjectNode result = read("goal-inspect.json"); result.put("worldId", f.store.inspect().path("worldId").asText()).put("requestId", id).put("expectedRevision", f.store.inspect().path("revision").asInt()); return result; }
    private static JsonNode action(JsonNode record) { for (JsonNode stage : record.path("stages")) if (stage.path("stage").asText().equals("resolve") && stage.path("status").asText().equals("completed")) return stage.path("output").path("action"); throw new AssertionError("没有实际行动转换结果"); }
    private static void rejectUnchanged(Fixture f, Checked action) throws Exception { byte[] before = Files.readAllBytes(f.path); JsonNode history = f.store.history().deepCopy(); rejects(action); check(Arrays.equals(before, Files.readAllBytes(f.path)), "非法动作后正式文件字节不变"); equal(f.store.history(), history, "非法动作后完整历史不变"); }
    private static void rejects(Checked action) throws Exception { try { action.run(); } catch (IllegalArgumentException | IllegalStateException | java.io.IOException expected) { return; } throw new AssertionError("应拒绝的操作被接受"); }
    private static void run(String name, Checked action) { ObjectNode result = JSON.createObjectNode().put("name", name); try { action.run(); result.put("passed", true); } catch (Exception | AssertionError failure) { result.put("passed", false).put("error", failure.getClass().getSimpleName() + ": " + failure.getMessage()); } RESULTS.add(result); }
    private static void equal(Object actual, Object expected, String why) { boolean same = actual instanceof JsonNode a && expected instanceof JsonNode b ? Protocol.canonical(a).equals(Protocol.canonical(b)) : java.util.Objects.equals(actual, expected); check(same, why + "；预期=" + expected + "；实际=" + actual); }
    private static void check(boolean condition, String why) { if (!condition) throw new AssertionError(why); }
    @FunctionalInterface private interface Checked { void run() throws Exception; }
}
