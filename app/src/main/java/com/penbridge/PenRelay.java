package com.penbridge;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;

/**
 * 笔输入转发器。华图的画布在笔画开始时锁定工具，而笔一接触屏幕，
 * 驱动每个硬件帧都会重新断言 BTN_TOUCH=1，直接向 evdev 注入抬笔会被
 * 同一帧覆盖，Android 永远收不到 ACTION_UP。
 * 这里用原生守护进程独占（EVIOCGRAB）真实笔节点，再用 uinput 复刻一个
 * 同参数的虚拟笔转发给系统，需要断笔时由守护进程在虚拟笔上发一次干净的
 * 抬笔+落笔，华图就会把后半段当成新的一笔（用新工具）。
 */
public final class PenRelay {
    public interface Listener {
        void onPenEvent(int type, int code, int value);
        default void onAbort() {
        }
    }

    private static final String TARGET = "/data/local/tmp/penbridge-pend";

    private Process process;
    private OutputStream stdin;
    private Thread reader;
    private volatile boolean ready;

    public boolean isReady() {
        return ready;
    }

    public static boolean install(Context ctx) {
        try {
            File dst = new File(ctx.getFilesDir(), "pend");
            InputStream in = ctx.getAssets().open("pend");
            FileOutputStream fos = new FileOutputStream(dst);
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) {
                fos.write(buf, 0, n);
            }
            fos.close();
            in.close();
            dst.setReadable(true, false);
            dst.setExecutable(true, false);
            String out = RootShell.run("cp " + dst.getAbsolutePath() + " " + TARGET
                    + " && chmod 755 " + TARGET + " && echo OK");
            boolean ok = out.contains("OK");
            if (!ok) {
                LogBuffer.add("安装笔转发失败: " + out.trim());
            }
            return ok;
        } catch (Exception e) {
            LogBuffer.add("安装笔转发异常: " + e);
            return false;
        }
    }

    public boolean start(String node, Listener listener) {
        stop();
        try {
            process = new ProcessBuilder("su", "-c", "exec " + TARGET + " " + node)
                    .redirectErrorStream(true)
                    .start();
            stdin = process.getOutputStream();
            reader = new Thread(() -> readLoop(listener), "pen-relay");
            reader.start();
            return true;
        } catch (Exception e) {
            LogBuffer.add("启动笔转发失败: " + e);
            process = null;
            return false;
        }
    }

    private void readLoop(Listener listener) {
        try {
            BufferedReader br = new BufferedReader(new InputStreamReader(
                    process.getInputStream()), 8192);
            String line;
            while ((line = br.readLine()) != null) {
                if (line.startsWith("E ")) {
                    int a = line.indexOf(' ');
                    int b = line.indexOf(' ', a + 1);
                    int c = line.indexOf(' ', b + 1);
                    if (c > 0) {
                        try {
                            int type = Integer.parseInt(line.substring(a + 1, b));
                            int code = Integer.parseInt(line.substring(b + 1, c));
                            int value = Integer.parseInt(line.substring(c + 1).trim());
                            listener.onPenEvent(type, code, value);
                        } catch (NumberFormatException ignored) {
                        }
                    }
                } else if (line.startsWith("READY")) {
                    ready = true;
                    LogBuffer.add("笔转发就绪 " + line.substring(5).trim());
                } else if (line.startsWith("B ")) {
                    LogBuffer.add("转发断笔 " + line.substring(2));
                    if (line.startsWith("B ABORT")) {
                        listener.onAbort();
                    }
                } else if (line.startsWith("ERR")) {
                    LogBuffer.add("笔转发 " + line);
                }
            }
        } catch (Exception ignored) {
        }
        ready = false;
    }

    public void send(String cmd) {
        OutputStream out = stdin;
        if (out == null) {
            return;
        }
        try {
            out.write((cmd + "\n").getBytes("UTF-8"));
            out.flush();
        } catch (Exception e) {
            LogBuffer.add("笔转发写入失败: " + e);
        }
    }

    public void stop() {
        ready = false;
        try {
            if (stdin != null) {
                stdin.close();
            }
        } catch (Exception ignored) {
        }
        stdin = null;
        Process p = process;
        process = null;
        if (p != null) {
            p.destroy();
        }
        RootShell.run("pkill -f " + TARGET);
    }
}
