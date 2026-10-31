package com.jdsp.backend;

import com.jdsp.cli.Assert;
import com.jdsp.cli.Suite;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * v2.1.6: the diagnostic parameter range (30000-32999: mix check 30023-30028, capture tap
 * 32001/32002) is runtime state, not a setting.
 *
 * <p>On the device one {@code jdspcli set 30025} ended up in config.json, was replayed as a 27th
 * parameter after the next daemon start and broke the 26 parameter baseline. The three
 * invariants are: never stored, never replayed (the attach replay reads {@code paramsFor}), never
 * shown by dump-config (it serialises the same maps) - so the tests assert on the maps and on the
 * two places that feed them.
 *
 * <p>The JSON round trip itself is not exercised here: {@code ConfigStore} uses {@code org.json},
 * which only exists in android.jar and the host suite runs <em>without</em> it on purpose (that is
 * what proves the tested classes have no Android dependency). The two functions that the parse
 * path calls, {@link ConfigStore#dropDiagnostics} and the setters, are covered directly.
 */
public final class DiagnosticParamPersistenceTest {
    private DiagnosticParamPersistenceTest() {}

    private static final byte[] FOUR = {1, 0, 0, 0};
    private static final byte[] TWELVE = {
            (byte) 0xcd, (byte) 0xcc, (byte) 0xcc, (byte) 0xbd,
            0x00, 0x00, 0x70, 0x42, 0x00, 0x00, 0x00, 0x00};

    private static File tmp(String name) {
        return new File(System.getProperty("java.io.tmpdir"), name);
    }

    private static boolean hasDiagnostic(Map<Integer, byte[]> map) {
        for (Integer id : map.keySet()) {
            if (com.jdsp.common.DiagnosticParams.isDiagnostic(id.intValue())) {
                return true;
            }
        }
        return false;
    }

    public static void run(Suite suite) {
        suite.test("diagnostic: a global write reaches neither the map nor the replay set", () -> {
            File f = tmp("jdspd-test-diag-global.json");
            f.delete();
            ConfigStore config = new ConfigStore(f);
            config.setGlobalParam(30025, FOUR);
            config.setParamFor("", 32001, FOUR);
            Assert.eq(0, config.globalParamCount(), "diagnostic ids never enter globalParams");
            Assert.eq(null, config.getParamFor("", 30025), "no stored value to read back");
            Assert.isTrue(!hasDiagnostic(config.paramsFor("")),
                    "the attach replay (paramsFor) has no diagnostic id");
        });

        suite.test("diagnostic: per app writes are filtered as well", () -> {
            File f = tmp("jdspd-test-diag-perapp.json");
            f.delete();
            ConfigStore config = new ConfigStore(f);
            config.setPerAppParam("com.example.player", 30023, FOUR);
            config.setParamFor("com.example.player", 30026, FOUR);
            Assert.eq(0, config.perAppParamsFor("com.example.player").size(),
                    "no per app entry for the diagnostic range");
            Assert.isTrue(!hasDiagnostic(config.paramsFor("com.example.player")),
                    "the per app replay has no diagnostic id either");
        });

        suite.test("diagnostic: real parameters are still stored next to them", () -> {
            File f = tmp("jdspd-test-diag-mixed.json");
            f.delete();
            ConfigStore config = new ConfigStore(f);
            config.setGlobalParam(1500, TWELVE);
            config.setGlobalParam(30024, FOUR);
            Assert.eq(1, config.globalParamCount(), "only param 1500 is stored");
            Assert.hex(config.getParamFor("", 1500), "cdccccbd0000704200000000");
            Assert.isTrue(config.paramsFor("").containsKey(Integer.valueOf(1500)),
                    "the real parameter is part of the replay set");
            Assert.isTrue(!config.paramsFor("").containsKey(Integer.valueOf(30024)),
                    "the runs counter is not");
        });

        suite.test("diagnostic: the load time filter drops them from a parsed map", () -> {
            Map<Integer, byte[]> map = new LinkedHashMap<Integer, byte[]>();
            map.put(Integer.valueOf(1500), TWELVE);
            map.put(Integer.valueOf(30025), FOUR);
            map.put(Integer.valueOf(32002), FOUR);
            map.put(Integer.valueOf(33100), FOUR); // outside the range: kept
            int dropped = ConfigStore.dropDiagnostics(map);
            Assert.eq(2, dropped, "two diagnostic entries dropped");
            Assert.eq(2, map.size(), "1500 and 33100 survive");
            Assert.isTrue(map.containsKey(Integer.valueOf(1500)), "the real parameter survives");
            Assert.isTrue(!map.containsKey(Integer.valueOf(30025)), "the armed flag is gone");
            Assert.isTrue(!map.containsKey(Integer.valueOf(32002)), "the tap status id is gone");
        });

        suite.test("diagnostic: the filter is a no-op on an empty map", () -> {
            Map<Integer, byte[]> map = new LinkedHashMap<Integer, byte[]>();
            Assert.eq(0, ConfigStore.dropDiagnostics(map), "empty map");
            Assert.eq(0, ConfigStore.dropDiagnostics(null), "null map");
        });
    }
}
