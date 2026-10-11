package creativeworld;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;

final class Protocol {
    static final ObjectMapper JSON = new ObjectMapper()
        .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    static ObjectNode object(JsonNode node, Set<String> required, Set<String> optional) {
        if (node == null || !node.isObject()) throw bad("需要 JSON 对象");
        node.fieldNames().forEachRemaining(key -> {
            if (!required.contains(key) && !optional.contains(key)) throw bad("不支持的字段：" + key);
        });
        for (String key : required) if (!node.has(key)) throw bad("缺少字段：" + key);
        return (ObjectNode) node;
    }
    static ObjectNode object(JsonNode node, String... required) { return object(node, Set.of(required), Set.of()); }
    static String text(JsonNode node, String label) {
        JsonNode value = node != null && node.isObject() ? node.get(label) : node;
        if (value == null || !value.isTextual() || value.asText().isBlank()) throw bad("需要非空文本：" + label);
        return value.asText();
    }
    static String id(JsonNode node, String key) {
        String value = text(node, key);
        if (value.startsWith("@")) throw bad("标识不能使用保留前缀 @：" + key);
        return value;
    }
    static int integer(JsonNode node, String key) {
        JsonNode value = node.get(key);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 0)
            throw bad("需要非负整数：" + key);
        return value.intValue();
    }
    static void version(JsonNode node) { if (integer(node, "schemaVersion") != 1) throw bad("不支持的协议版本"); }
    static String moduleVersion(JsonNode holder) {
        if (holder == null || !holder.isObject()) throw bad("模块版本所属数据须为对象");
        return holder.has("moduleVersion") ? moduleVersion(text(holder, "moduleVersion")) : "1";
    }
    static String moduleVersion(String value) {
        if (value == null || value.isBlank() || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0)
            throw bad("行为模块版本须为非空单行文本");
        return value;
    }
    static ArrayNode array(JsonNode node, String key) {
        if (!node.path(key).isArray()) throw bad("需要数组：" + key);
        return (ArrayNode) node.path(key);
    }
    static JsonNode scalar(JsonNode value, String label) {
        if (value == null || !(value.isNull() || value.isTextual() || value.isBoolean() || value.isNumber()))
            throw bad("需要 JSON 标量：" + label);
        if (value.isNumber()) {
            Number number = value.numberValue();
            if ((number instanceof Double d && !Double.isFinite(d)) || (number instanceof Float f && !Float.isFinite(f)))
                throw bad("数值必须有限：" + label);
        }
        return value;
    }
    static void data(JsonNode value) {
        if (value.isObject() || value.isArray()) value.forEach(Protocol::data);
        else scalar(value, "资料内容");
    }
    static ObjectNode canonicalObject(JsonNode value) { return (ObjectNode) canonical(value); }
    static JsonNode canonical(JsonNode value) {
        if (value.isObject()) {
            ObjectNode result = JSON.createObjectNode();
            TreeSet<String> names = new TreeSet<>(); value.fieldNames().forEachRemaining(names::add);
            for (String name : names) result.set(name, canonical(value.get(name)));
            return result;
        }
        if (value.isArray()) {
            ArrayNode result = JSON.createArrayNode(); value.forEach(item -> result.add(canonical(item))); return result;
        }
        if (value.isNumber()) {
            scalar(value, "协议内容");
            java.math.BigDecimal number = value.decimalValue().stripTrailingZeros();
            return number.scale() <= 0 ? JSON.getNodeFactory().numberNode(number.toBigIntegerExact())
                : JSON.getNodeFactory().numberNode(number);
        }
        return scalar(value, "协议内容").deepCopy();
    }
    static IllegalArgumentException bad(String message) { return new IllegalArgumentException(message); }
}
