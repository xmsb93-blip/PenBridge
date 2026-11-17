package com.penbridge;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

public final class RootShell {
    private RootShell() {
    }

    public static String run(String cmd) {
        try {
            Process p = new ProcessBuilder("su", "-c", cmd)
                    .redirectErrorStream(true)
                    .start();
            String out = readAll(p.getInputStream());
            p.waitFor();
            return out;
        } catch (Exception e) {
            return "ERR " + e;
        }
    }

    public static boolean available() {
        String out = run("id");
        return out.contains("uid=0");
    }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        return bos.toString("UTF-8");
    }
}
