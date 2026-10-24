package com.jdsp.common;

import com.jdsp.cli.Assert;
import com.jdsp.cli.Suite;

import java.io.File;
import java.io.FileOutputStream;

/** Pure Java regression tests for the backend's shared decision helpers. */
public final class BackendCommonTest {
    private BackendCommonTest() {}

    public static void run(Suite suite) {
        suite.test("common: unknown package uses global parameters", new com.jdsp.cli.Body() {
            public void run() {
                Assert.eq(ParamScope.GLOBAL, ParamScope.decide(null, true, true));
                Assert.eq(ParamScope.GLOBAL, ParamScope.decide("", true, true));
            }
        });
        suite.test("common: explicit policy selects per-app parameters", new com.jdsp.cli.Body() {
            public void run() {
                Assert.eq(ParamScope.PER_APP, ParamScope.decide("a.music", false, true));
                Assert.eq(ParamScope.PER_APP, ParamScope.decide("a.music", true, false));
                Assert.eq(ParamScope.GLOBAL, ParamScope.decide("a.music", false, false));
                Assert.eq(ParamScope.GLOBAL, ParamScope.decide("other.music", false, false));
            }
        });
        suite.test("common: missing or stale endpoint files need a rewrite", new com.jdsp.cli.Body() {
            public void run() throws Exception {
                File dir = tempDir();
                File port = new File(dir, EndpointFiles.PORT_FILE);
                File token = new File(dir, EndpointFiles.TOKEN_FILE);
                String p = "37501\n";
                String t = "abcdef0123456789\n";
                Assert.isTrue(EndpointFiles.decide(dir, port, token, p, t).rewrite, "missing endpoint files must be rewritten");
                write(port, p);
                write(token, t);
                Assert.isTrue(EndpointFiles.decide(dir, port, token, p, t).upToDate, "matching endpoint files are current");
                Assert.isTrue(!EndpointFiles.decide(dir, port, token, "37502\n", t).upToDate, "stale port is not current");
                Assert.isTrue(EndpointFiles.decide(dir, port, token, "37502\n", t).rewrite, "stale port needs rewrite");
                write(token, "short\n");
                Assert.isTrue(EndpointFiles.decide(dir, port, token, p, t).rewrite, "truncated token needs rewrite");
                deleteTree(dir);
            }
        });
        suite.test("common: absent App directory is not rewritten", new com.jdsp.cli.Body() {
            public void run() throws Exception {
                File dir = tempDir();
                File missing = new File(dir, "missing");
                EndpointFiles.Decision d = EndpointFiles.decide(missing,
                        new File(missing, EndpointFiles.PORT_FILE),
                        new File(missing, EndpointFiles.TOKEN_FILE), "1\n", "token\n");
                Assert.isTrue(d.appMissing, "missing App directory is reported");
                Assert.isTrue(!d.rewrite, "missing App directory must not be created");
                Assert.isTrue(!d.upToDate, "missing endpoint files are not current");
                deleteTree(dir);
            }
        });
    }

    private static File tempDir() throws Exception {
        File dir = File.createTempFile("jdsp-common-test", "");
        if (!dir.delete() || !dir.mkdir()) {
            throw new IllegalStateException("cannot create temporary directory " + dir);
        }
        return dir;
    }

    private static void write(File file, String value) throws Exception {
        FileOutputStream out = new FileOutputStream(file);
        try {
            out.write(value.getBytes("UTF-8"));
        } finally {
            out.close();
        }
    }

    private static void deleteTree(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteTree(child);
                }
            }
        }
        if (!file.delete()) {
            file.deleteOnExit();
        }
    }
}
