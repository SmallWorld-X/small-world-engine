package creativeworld;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Set;

/** 构造器只读独立快照，返回待合并的资料贡献，不持有正式存档。 */
public interface WorldConstructor {
    String id();
    default String version() { return "1"; }
    Set<String> dependencies();
    default String sectionName() { return id(); }
    ObjectNode construct(Context context);

    final class Context {
        private final ObjectNode blueprint, seed;
        private final JsonNode section;

        Context(ObjectNode blueprint, ObjectNode seed, String sectionName) {
            this.blueprint = blueprint.deepCopy();
            this.seed = seed.deepCopy();
            this.section = blueprint.path("sections").path(sectionName).deepCopy();
        }

        public ObjectNode blueprint() { return blueprint.deepCopy(); }
        public ObjectNode seed() { return seed.deepCopy(); }
        public JsonNode section() { return section.deepCopy(); }
    }
}
