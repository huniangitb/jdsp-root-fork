package com.jdsp.cli;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The minimal JSON reader/writer used by the socket transport. */
public final class JsonTest {
    private JsonTest() {}

    public static void run(Suite suite) {
        suite.test("json: object roundtrip keeps key order", () -> {
            String text = "{\"a\":1,\"b\":[true,null,\"x\"],\"c\":{\"d\":1.5}}";
            Object parsed = Json.parse(text);
            Assert.eq(text, Json.write(parsed));
        });

        suite.test("json: escapes roundtrip", () -> {
            String text = "{\"s\":\"a\\\"b\\\\c\\n\\t\"}";
            Map<String, Object> object = Json.object(Json.parse(text));
            Assert.eq("a\"b\\c\n\t", Json.string(object, "s"));
            Assert.eq(text, Json.write(object));
        });

        suite.test("json: unicode escape", () -> {
            Map<String, Object> object = Json.object(Json.parse("{\"u\":\"\\u4e2d\"}"));
            Assert.eq("\u4e2d", Json.string(object, "u"));
            Assert.eq("{\"u\":\"\u4e2d\"}", Json.write(object));
        });

        suite.test("json: typed helpers and defaults", () -> {
            Map<String, Object> object = Json.object(Json.parse(
                    "{\"i\":7,\"b\":true,\"s\":\"v\",\"n\":null}"));
            Assert.eq(7, Json.intValue(object, "i", 0));
            Assert.eq(0, Json.intValue(object, "missing", 0));
            Assert.eq(Boolean.TRUE, Json.booleanValue(object, "b", false));
            Assert.eq(Boolean.FALSE, Json.booleanValue(object, "missing", false));
            Assert.eq("v", Json.string(object, "s"));
            Assert.eq(null, Json.string(object, "n"));
            Assert.eq(null, Json.string(object, "missing"));
            Assert.eq(null, Json.array(object.get("i")));
        });

        suite.test("json: invalid input is a CliException(2)", () -> {
            Assert.cliCode(Exit.UNAVAILABLE, () -> Json.parse("{bad}"));
            Assert.cliCode(Exit.UNAVAILABLE, () -> Json.parse("[] trailing"));
            Assert.cliCode(Exit.UNAVAILABLE, () -> Json.parse(null));
            Assert.cliCode(Exit.UNAVAILABLE, () -> Json.parse("{\"a\":}"));
        });

        suite.test("json: write scalars and nested values", () -> {
            Assert.eq("null", Json.write(null));
            Assert.eq("true", Json.write(Boolean.TRUE));
            Assert.eq("42", Json.write(Long.valueOf(42)));
            Assert.eq("\"hi\"", Json.write("hi"));
            List<Object> list = new ArrayList<Object>();
            list.add(Long.valueOf(1));
            list.add("two");
            Map<String, Object> wrapper = new LinkedHashMap<String, Object>();
            wrapper.put("list", list);
            Assert.eq("{\"list\":[1,\"two\"]}", Json.write(wrapper));
        });

        suite.test("json: parse array root", () -> {
            List<Object> list = Json.array(Json.parse("[1,2,3]"));
            Assert.eq(3, list.size());
            Assert.eq(Long.valueOf(2), list.get(1));
        });
    }
}
