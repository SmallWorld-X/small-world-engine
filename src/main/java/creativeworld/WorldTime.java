package creativeworld;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import static creativeworld.Protocol.*;

/** 明确声明的年度时钟；没有时钟的世界不创建默认年份。 */
final class WorldTime {
    private int year;
    private WorldTime(int year) { this.year = year; }

    static WorldTime from(JsonNode clock) {
        object(clock, "unit", "year");
        if (!text(clock, "unit").equals("year")) throw bad("当前世界时间只支持明确的year单位");
        return new WorldTime(integer(clock, "year"));
    }
    int year() { return year; }
    ObjectNode json() { return JSON.createObjectNode().put("unit", "year").put("year", year); }
    WorldTime copy() { return new WorldTime(year); }
    void advance(JsonNode effect) {
        object(effect, "type", "expectedYear", "toYear");
        if (integer(effect, "expectedYear") != year) throw bad("时间旧值不一致");
        int next = integer(effect, "toYear");
        if (next <= year) throw bad("世界时间必须严格向前推进");
        year = next;
    }
    void verify(JsonNode recorded) {
        if (from(recorded).year != year) throw bad("历史事件时钟与重放结果不一致");
    }
}
