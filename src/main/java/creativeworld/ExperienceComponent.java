package creativeworld;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Locale;

/** 本地演绎阶段只产生结果；世界提交仍由编排器和存储内核负责。 */
public interface ExperienceComponent {
    String id();
    String version();
    Stage stage();
    ObjectNode run(Context context);

    enum Stage {
        PREPARE, CANDIDATES, SELECT, RESOLVE, FEEDBACK;
        public String key() { return name().toLowerCase(Locale.ROOT); }
    }

    final class Context {
        private final ObjectNode request, actorView, outputs, receipt;

        public Context(ObjectNode request, ObjectNode actorView, ObjectNode outputs, ObjectNode receipt) {
            if (request == null || actorView == null || outputs == null)
                throw Protocol.bad("演绎上下文的请求、人物视图和阶段结果不能为空");
            this.request = request.deepCopy();
            this.actorView = actorView.deepCopy();
            this.outputs = outputs.deepCopy();
            this.receipt = receipt == null ? Protocol.JSON.createObjectNode() : receipt.deepCopy();
        }

        public ObjectNode request() { return request.deepCopy(); }
        public ObjectNode actorView() { return actorView.deepCopy(); }
        public ObjectNode outputs() { return outputs.deepCopy(); }
        public ObjectNode receipt() { return receipt.deepCopy(); }
    }
}
