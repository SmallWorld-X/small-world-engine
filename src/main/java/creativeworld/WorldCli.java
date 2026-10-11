package creativeworld;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.*;
import static creativeworld.Protocol.*;

/** CLI 只转换参数与 JSON；规则、事务和投影由内核处理。 */
public final class WorldCli {
    public static void main(String[] args) {
        try {
            if (args.length == 0) throw bad("需要命令：construct、confirm、prepare-draft、prepare、init、inspect、view、view-at、apply、history、step、experience、pursue 或 continue");
            WorldEngine engine = new WorldEngine();
            JsonNode result;
            switch (args[0]) {
                case "construct" -> {
                    if (args.length != 2 && args.length != 3) throw bad("construct 需要蓝图文件和可选草案输出文件");
                    ObjectNode draft = engine.genesis().build(JSON.readTree(Path.of(args[1]).toFile()));
                    if (args.length == 3) {
                        distinct(Path.of(args[1]), Path.of(args[2]));
                        saveNewDraft(Path.of(args[2]), draft);
                        result = JSON.createObjectNode().put("schemaVersion", 1).put("status", "draft-created")
                            .put("worldId", text(draft.path("seed"), "worldId"))
                            .put("draftFile", Path.of(args[2]).toAbsolutePath().normalize().toString());
                        ((ObjectNode) result).set("construction", draft.get("construction"));
                    } else result = draft;
                }
                case "confirm" -> {
                    count(args, 3); distinct(Path.of(args[1]), Path.of(args[2]));
                    JsonNode draft = JSON.readTree(Path.of(args[1]).toFile());
                    ObjectNode seed = engine.genesis().confirmSeed(draft);
                    result = engine.openWorld(Path.of(args[2])).initialize(seed);
                }
                case "prepare-draft" -> {
                    count(args, 3);
                    result = engine.preparation().analyzeDraft(engine.genesis(),
                        JSON.readTree(Path.of(args[1]).toFile()), JSON.readTree(Path.of(args[2]).toFile()));
                }
                case "prepare" -> {
                    count(args, 3);
                    result = engine.preparation().analyze(engine.openWorld(Path.of(args[1])),
                        JSON.readTree(Path.of(args[2]).toFile()));
                }
                case "init" -> {
                    count(args, 3); distinct(Path.of(args[1]), Path.of(args[2]));
                    result = engine.openWorld(Path.of(args[2])).initialize(JSON.readTree(Path.of(args[1]).toFile()));
                }
                case "inspect" -> { count(args, 2); result = engine.openWorld(Path.of(args[1])).inspect(); }
                case "view" -> { count(args, 3); result = engine.openWorld(Path.of(args[1])).view(args[2]); }
                case "view-at" -> { count(args, 4); result = engine.openWorld(Path.of(args[1])).viewAt(args[2], Integer.parseInt(args[3])); }
                case "apply" -> {
                    count(args, 3); distinct(Path.of(args[2]), Path.of(args[1]));
                    result = engine.openWorld(Path.of(args[1])).apply(JSON.readTree(Path.of(args[2]).toFile()));
                }
                case "history" -> { count(args, 2); result = engine.openWorld(Path.of(args[1])).history(); }
                case "step" -> {
                    if (args.length != 4 && args.length != 5) throw bad("step 需要世界文件、模块、输入文件及可选模块版本");
                    distinct(Path.of(args[3]), Path.of(args[1]));
                    WorldStore world = engine.openWorld(Path.of(args[1]));
                    JsonNode input = JSON.readTree(Path.of(args[3]).toFile());
                    result = args.length == 5 ? world.step(args[2], args[4], input) : world.step(args[2], input);
                }
                case "experience" -> {
                    count(args, 4); distinct(Path.of(args[2]), Path.of(args[1]));
                    distinct(Path.of(args[2]), Path.of(args[3])); distinct(Path.of(args[1]), Path.of(args[3]));
                    result = engine.experience().run(engine.openWorld(Path.of(args[1])),
                        JSON.readTree(Path.of(args[2]).toFile()), Path.of(args[3]));
                }
                case "pursue" -> {
                    if (args.length != 4 && args.length != 5) throw bad("pursue 需要世界文件、目标请求、过程目录及可选尝试上限");
                    Path input = Path.of(args[2]), directory = Path.of(args[3]);
                    distinct(input, Path.of(args[1])); distinct(input, directory.resolve("journey.json"));
                    distinct(input, directory.resolve("manifest.lock"));
                    for (int step = 1; step <= 32; step++) {
                        String name = "step-%03d.json".formatted(step);
                        distinct(input, directory.resolve(name)); distinct(input, directory.resolve(name + ".lock"));
                    }
                    int budget = args.length == 5 ? Integer.parseInt(args[4]) : 8;
                    result = engine.pursue(engine.openWorld(Path.of(args[1])), JSON.readTree(input.toFile()), directory, budget);
                }
                case "continue" -> {
                    if (args.length != 4 && args.length != 5) throw bad("continue需要世界文件、活动请求、过程目录及可选累计尝试上限");
                    Path input = Path.of(args[2]), directory = Path.of(args[3]);
                    distinct(input, Path.of(args[1])); distinct(input, directory.resolve("continuation.json"));
                    distinct(input, directory.resolve("continuation.lock"));
                    for (int turn = 1; turn <= 32; turn++) {
                        Path file = directory.resolve("turn-%03d.json".formatted(turn));
                        distinct(input, file); distinct(input, file.resolveSibling(file.getFileName() + ".lock"));
                    }
                    int budget = args.length == 5 ? Integer.parseInt(args[4]) : 8;
                    result = engine.continueWorld(engine.openWorld(Path.of(args[1])), JSON.readTree(input.toFile()), directory, budget);
                }
                default -> throw bad("命令不支持");
            }
            System.out.println(JSON.writerWithDefaultPrettyPrinter().writeValueAsString(result));
        } catch (Exception failure) {
            ObjectNode error = JSON.createObjectNode();
            error.putObject("error").put("type", failure.getClass().getSimpleName()).put("message", failure.getMessage() == null ? "命令失败" : failure.getMessage());
            System.err.println(error); System.exit(1);
        }
    }
    private static void count(String[] args, int size) { if (args.length != size) throw bad("命令参数数量不正确"); }
    @SuppressWarnings("try") // 锁的持有期保护同一路径的草案首次保存。
    private static synchronized void saveNewDraft(Path path, ObjectNode draft) throws Exception {
        Path target = path.toAbsolutePath().normalize();
        Files.createDirectories(target.getParent());
        target = target.getParent().toRealPath().resolve(target.getFileName());
        try (FileChannel channel = FileChannel.open(target.resolveSibling(target.getFileName() + ".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignored = channel.lock()) {
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw bad("草案文件已经存在，请使用新的输出文件保存候选版本");
            Path temporary = Files.createTempFile(target.getParent(), ".world-draft-", ".tmp");
            try {
                JSON.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), draft);
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            } finally { Files.deleteIfExists(temporary); }
        }
    }
    private static void distinct(Path input, Path store) throws Exception {
        Path source = input.toAbsolutePath().normalize();
        Path target = store.toAbsolutePath().normalize();
        if (Files.exists(source)) source = source.toRealPath();
        if (Files.exists(target)) target = target.toRealPath();
        if (source.toString().equalsIgnoreCase(target.toString())
            || (Files.exists(source) && Files.exists(target) && Files.isSameFile(source, target)))
            throw bad("世界存储不能覆盖输入文件");
    }
}
