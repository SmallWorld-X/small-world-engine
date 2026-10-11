package creativeworld;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import static creativeworld.Protocol.*;

/** 本地共同装配入口；世界状态、事件和存档仍由已有内核负责。 */
public final class WorldEngine {
    private final WorldGenesis genesis = new WorldGenesis();
    private final WorldPreparation preparation = new WorldPreparation();
    private final ExperienceEngine experience = new ExperienceEngine();
    private final Map<ActionKey, ActionBinding> actions = new LinkedHashMap<>();

    public WorldEngine register(WorldConstructor constructor) { genesis.register(constructor); return this; }
    public WorldEngine intercept(CreationInterceptor interceptor) { genesis.intercept(interceptor); return this; }
    public WorldEngine register(ExperienceComponent component) { experience.register(component); return this; }

    public synchronized WorldEngine register(ActionModule module) {
        Objects.requireNonNull(module, "行为模块不能为空");
        String id = module.id();
        if (id == null || id.isBlank() || id.startsWith("@")) throw bad("模块标识无效");
        ActionKey identity = new ActionKey(id, moduleVersion(module.version()));
        ActionBinding binding = new ActionBinding(identity, module);
        check(binding);
        if (actions.putIfAbsent(identity, binding) != null) throw bad("行为模块标识与版本已经注册");
        return this;
    }

    public WorldGenesis genesis() { return genesis; }
    public WorldPreparation preparation() { return preparation; }
    public ExperienceEngine experience() { return experience; }

    /** 每次打开都装配同一宿主已注册的行为；不创建或修改正式世界文件。 */
    public WorldStore openWorld(Path file) {
        List<ActionBinding> snapshot;
        synchronized (this) { snapshot = List.copyOf(actions.values()); }
        WorldStore world = new WorldStore(file);
        for (ActionBinding binding : snapshot) {
            check(binding);
            world.register(binding.module);
            check(binding);
        }
        return world;
    }

    public ObjectNode pursue(WorldStore world, JsonNode request, Path directory, int maxAttempts) throws IOException {
        return GoalJourney.run(world, experience, request, directory, maxAttempts);
    }

    public ObjectNode continueWorld(WorldStore world, JsonNode request, Path directory, int maxAttempts) throws IOException {
        return WorldContinuation.run(world, experience, request, directory, maxAttempts);
    }

    private static void check(ActionBinding binding) {
        if (!binding.identity.id.equals(binding.module.id())
            || !binding.identity.version.equals(moduleVersion(binding.module.version())))
            throw bad("行为模块在宿主注册后改变了标识或版本");
    }

    private record ActionKey(String id, String version) {}
    private record ActionBinding(ActionKey identity, ActionModule module) {}
}
