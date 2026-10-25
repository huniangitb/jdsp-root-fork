package com.jdsp.common;

import com.jdsp.cli.Assert;
import com.jdsp.cli.Suite;

import java.io.File;
import java.util.Map;
import java.util.Scanner;

/**
 * The canonical baseline embedded in the daemon: self-consistency plus parity with the
 * scripts/baseline.json copy used by the shell/python test drivers. The JSON path comes
 * from the -Djdsp.baseline.json property (set by test/run_tests.sh); without the property
 * the parity check is skipped (the daemon constant itself is still verified).
 */
public final class ParamBaselineTest {
    private ParamBaselineTest() {}

    public static void run(Suite suite) {
        suite.test("baseline: 26 well-formed entries, ids unique and in order", () -> {
            Map<Integer, byte[]> params = ParamBaseline.params();
            Assert.eq(26, params.size());
            Assert.eq(26, ParamBaseline.COUNT);
            Assert.eq(ParamBaseline.IDS.length, ParamBaseline.HEX_VALUES.length);
            for (Map.Entry<Integer, byte[]> e : params.entrySet()) {
                Assert.isTrue(e.getValue() != null && e.getValue().length > 0,
                        "empty value for param " + e.getKey());
            }
        });

        suite.test("baseline: regression-critical values (postGain=0, R2R off)", () -> {
            Map<Integer, byte[]> params = ParamBaseline.params();
            // 1500 output control: -0.1 dB, 60 ms, postGain 0 dB
            Assert.hex(params.get(1500), "cdccccbd0000704200000000");
            // 1218 NOS R2R enable: off
            Assert.hex(params.get(1218), "0000");
            // 112 bass boost max gain: 1
            Assert.hex(params.get(112), "0100");
            // every module enable in the baseline is off except 1202 (EQ)
            int[] enables = {1200, 1201, 1203, 1204, 1205, 1206, 1208, 1210,
                    1212, 1213, 1214, 1215, 1216, 1217, 1218};
            for (int id : enables) {
                Assert.hex(params.get(id), "0000");
            }
            Assert.hex(params.get(1202), "0100");
        });

        suite.test("baseline: params() returns an independent copy each call", () -> {
            Map<Integer, byte[]> a = ParamBaseline.params();
            Map<Integer, byte[]> b = ParamBaseline.params();
            a.remove(1500);
            Assert.eq(26, b.size());
        });

        suite.test("baseline: scripts/baseline.json parity", () -> {
            String path = System.getProperty("jdsp.baseline.json", "");
            if (path == null || path.isEmpty()) {
                System.out.println("      (skipped: -Djdsp.baseline.json not set)");
                return;
            }
            File file = new File(path);
            Assert.isTrue(file.isFile(), "baseline JSON missing: " + path);
            StringBuilder sb = new StringBuilder();
            try (Scanner scanner = new Scanner(file, "UTF-8")) {
                while (scanner.hasNextLine()) {
                    sb.append(scanner.nextLine()).append('\n');
                }
            }
            String json = sb.toString();
            Map<Integer, byte[]> params = ParamBaseline.params();
            for (Map.Entry<Integer, byte[]> e : params.entrySet()) {
                StringBuilder hex = new StringBuilder();
                for (byte b : e.getValue()) {
                    hex.append(String.format("%02x", b));
                }
                Assert.contains(json, "\"" + e.getKey().intValue() + "\": \"" + hex + "\"");
            }
            Assert.contains(json, "jdspd-canonical-baseline");
        });
    }
}
