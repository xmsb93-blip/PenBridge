package com.penbridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PenService extends Service {
    private static final String CHANNEL = "penbridge";
    private static final int NOTIFICATION_ID = 41;
    private static final long DOUBLE_WINDOW_MS = 320;
    private static final long MAX_CLICK_MS = 900;
    private static final long LONG_PRESS_MS = 600;

    private static volatile boolean sRunning;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final java.util.concurrent.ExecutorService actionQueue =
            java.util.concurrent.Executors.newSingleThreadExecutor();
    private volatile boolean running;

    public static boolean isRunning() {
        return sRunning;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String cmd = intent == null ? null : intent.getStringExtra("cmd");
        LogBuffer.add("onStartCommand cmd=" + cmd);
        if ("dump".equals(cmd)) {
            dumpToFile();
        }
        if (!running) {
            running = true;
            sRunning = true;
            startForegroundCompat();
            LogBuffer.add("服务启动");
            new Thread(this::rootController, "root-controller").start();
            new Thread(this::accessWatchdog, "acc-watchdog").start();
        }
        return START_STICKY;
    }

    private void accessWatchdog() {
        int miss = 0;
        while (running) {
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                break;
            }
            if (!running) {
                break;
            }
            if (PenAccessibilityService.INSTANCE != null) {
                if (miss >= 3) {
                    LogBuffer.add("无障碍已恢复");
                }
                miss = 0;
                continue;
            }
            miss++;
            if (miss == 3 || miss % 12 == 0) {
                LogBuffer.add("无障碍未连接，尝试自动修复");
                AccessibilityFixer.repair(this);
            }
        }
    }

    private void startForegroundCompat() {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_edit)
                .setContentTitle("笔控运行中")
                .setContentText("单击笔键=草稿纸，笔尖重压=橡皮")
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, n);
        }
    }

    private void createChannel() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel(CHANNEL, "笔控", NotificationManager.IMPORTANCE_LOW);
        nm.createNotificationChannel(ch);
    }

    public static volatile boolean DEBUG_KEYS;

    private static final int ABS_X = 0x00;
    private static final int ABS_Y = 0x01;
    private static final int ABS_PRESSURE = 0x18;
    private static final long HARD_PRESS_STILL_MS = 100;
    private static final long HUATU_ERASER_LINGER_MS = 3000;
    private volatile boolean rootActive;
    private Runnable huatuRestore;
    private volatile boolean huatuArmed;
    private volatile long lastStrokeEndAt;
    private volatile long strokeActiveSince;
    private final List<Process> rootProcs = new ArrayList<>();
    private final Map<String, Watcher> watchers = new HashMap<>();
    private final Map<String, String> penNames = new HashMap<>();
    private PenRelay relay;
    private volatile String relayNode;
    private volatile long relayStartAt;
    private int relayMiss;
    private final PressureState relayState = new PressureState();
    private static final Pattern EVENT_NODE = Pattern.compile("event\\d+");

    private static final class Watcher {
        final boolean pressure;
        volatile boolean active = true;
        volatile Process process;
        final PressureState state = new PressureState();

        Watcher(boolean pressure) {
            this.pressure = pressure;
        }
    }

    private static final class PressureState {
        long strokeStart;
        long stillStart;
        long lastEventAt;
        long injectDownAt;
        long lastUpAt;
        long ignoreFireUntil;
        int max;
        volatile int lastX;
        volatile int lastY;
        int anchorX;
        int anchorY;
        boolean fired;
        boolean attempted;
        boolean touchDevice;
        boolean injected;
        boolean eraserStroke;
        int restoreGen;
        boolean restoreDone;
        boolean firedThisTouch;
        volatile boolean touching;
        volatile boolean expectInjectedUp;
        String device;
        int current;
    }

    private void rootController() {
        String out = RootShell.run("id");
        boolean ok = out.contains("uid=0");
        LogBuffer.add("root " + (ok ? "可用" : "不可用") + ": " + out.trim());
        if (!ok) {
            LogBuffer.add("仅使用无障碍按键通道");
            return;
        }
        rootActive = true;
        RootShell.run("pkill -f 'cat /dev/input/event'");
        PenRelay.install(this);
        handler.post(this::relayWatch);
        while (running) {
            syncWatches();
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                break;
            }
        }
        stopRootWatches();
    }

    /**
     * 只在华图前台时启用笔转发：独占真实笔节点并复刻一个虚拟笔，
     * 这样才能在笔画中途发出真正生效的抬笔事件。
     */
    private void relayWatch() {
        if (!running) {
            return;
        }
        PenAccessibilityService svc = PenAccessibilityService.INSTANCE;
        String pkg = svc == null ? null : svc.foregroundPackage();
        boolean want = rootActive && ActionRunner.HUATU.equals(pkg)
                && Prefs.eraserMode(this) == Prefs.MODE_PRESSURE;
        if (want && relay == null) {
            relayMiss = 0;
            startRelay(null);
        } else if (!want && relay != null) {
            if (++relayMiss >= 3) {
                relayMiss = 0;
                stopRelay();
            }
        } else if (want && relay != null) {
            relayMiss = 0;
            if (System.currentTimeMillis() - relayStartAt > 6000
                    && System.currentTimeMillis() - relayState.lastEventAt > 6000) {
                String active = penNodeForRelay();
                if (active != null && !active.equals(relayNode) && recent(active, 4000)) {
                    LogBuffer.add("笔转发节点无事件，改抓 " + active);
                    startRelay(active);
                }
            }
        }
        handler.postDelayed(this::relayWatch, 1500);
    }

    private boolean recent(String node, long ms) {
        synchronized (watchers) {
            Watcher w = watchers.get(node);
            return w != null && System.currentTimeMillis() - w.state.lastEventAt < ms;
        }
    }

    private String penNodeForRelay() {
        String best = null;
        long bestAt = 0;
        synchronized (watchers) {
            for (Map.Entry<String, Watcher> entry : watchers.entrySet()) {
                if (!entry.getValue().pressure) {
                    continue;
                }
                long at = entry.getValue().state.lastEventAt;
                if (at > bestAt) {
                    bestAt = at;
                    best = entry.getKey();
                }
            }
        }
        if (best == null) {
            for (Map.Entry<String, String> entry : penNames.entrySet()) {
                String name = entry.getValue();
                if (name != null && name.contains("P81c")) {
                    return entry.getKey();
                }
            }
        }
        return best;
    }

    private void startRelay(String forced) {
        stopRelay();
        String node = forced != null ? forced : penNodeForRelay();
        if (node == null) {
            LogBuffer.add("笔转发: 未找到笔节点");
            return;
        }
        relayState.device = node;
        relayState.strokeStart = 0;
        relayState.touching = false;
        relayState.lastEventAt = System.currentTimeMillis();
        PenRelay r = new PenRelay();
        if (r.start(node, new PenRelay.Listener() {
            @Override
            public void onPenEvent(int type, int code, int value) {
                relayState.lastEventAt = System.currentTimeMillis();
                onPressureEvent(type, code, value, relayState);
            }

            @Override
            public void onAbort() {
                // 断笔后用户马上就抬笔了，没有进入橡皮笔画，这里补一次回画笔
                huatuArmed = false;
                cancelHuatuRestore();
                restorePen(0);
            }
        })) {
            relay = r;
            relayNode = node;
            relayStartAt = System.currentTimeMillis();
            LogBuffer.add("笔转发启动 " + node);
        }
    }

    private void stopRelay() {
        PenRelay r = relay;
        relay = null;
        relayNode = null;
        if (r != null) {
            r.stop();
            relayState.strokeStart = 0;
            relayState.touching = false;
            LogBuffer.add("笔转发停止");
        }
    }

    private void syncWatches() {
        Map<String, Boolean> nodes = findPenNodes();
        boolean changed = false;
        synchronized (watchers) {
            for (Map.Entry<String, Watcher> entry : new ArrayList<>(watchers.entrySet())) {
                if (!nodes.containsKey(entry.getKey())) {
                    entry.getValue().active = false;
                    Process p = entry.getValue().process;
                    if (p != null) {
                        try {
                            p.destroy();
                        } catch (Exception ignored) {
                        }
                    }
                    watchers.remove(entry.getKey());
                    changed = true;
                }
            }
            for (Map.Entry<String, Boolean> entry : nodes.entrySet()) {
                if (!watchers.containsKey(entry.getKey())) {
                    final String node = entry.getKey();
                    final Watcher watcher = new Watcher(entry.getValue());
                    watchers.put(node, watcher);
                    new Thread(() -> watch(node, watcher),
                            "watch-" + node.substring(node.lastIndexOf('/') + 1)).start();
                    changed = true;
                }
            }
        }
        if (changed) {
            LogBuffer.add("笔事件监听节点: " + nodes);
        }
    }

    private Map<String, Boolean> findPenNodes() {
        Map<String, Boolean> nodes = new HashMap<>();
        String dump = RootShell.run("cat /proc/bus/input/devices");
        String[] blocks = dump.split("\n\n");
        for (String block : blocks) {
            boolean pressure = block.contains("NVTCapacitivePen");
            if (!pressure && !block.contains("Xiaomi Focus Pen")) {
                continue;
            }
            String name = "";
            for (String line : block.split("\n")) {
                if (line.startsWith("N: Name=")) {
                    name = line.substring(8).replace("\"", "").trim();
                }
            }
            if (name.contains("Relay")) {
                continue;
            }
            for (String line : block.split("\n")) {
                if (!line.startsWith("H: Handlers=")) {
                    continue;
                }
                Matcher m = EVENT_NODE.matcher(line);
                while (m.find()) {
                    String node = "/dev/input/" + m.group();
                    nodes.put(node, pressure);
                    penNames.put(node, name);
                }
            }
        }
        return nodes;
    }

    private void stopRootWatches() {
        rootActive = false;
        synchronized (rootProcs) {
            for (Process p : rootProcs) {
                try {
                    p.destroy();
                } catch (Exception ignored) {
                }
            }
            rootProcs.clear();
        }
    }

    private void watch(String device, Watcher watcher) {
        while (running && rootActive && watcher.active) {
            Process process = null;
            try {
                process = new ProcessBuilder("su", "-c", "cat " + device)
                        .redirectErrorStream(true)
                        .start();
                watcher.process = process;
                synchronized (rootProcs) {
                    rootProcs.add(process);
                }
                InputStream in = process.getInputStream();
                byte[] buf = new byte[24];
                while (running && rootActive && watcher.active) {
                    int read = readFully(in, buf);
                    if (read < 24) {
                        break;
                    }
                    int type = le16(buf, 16);
                    int code = le16(buf, 18);
                    int value = le32(buf, 20);
                    watcher.state.lastEventAt = System.currentTimeMillis();
                    if (watcher.pressure) {
                        watcher.state.device = device;
                        onPressureEvent(type, code, value, watcher.state);
                    } else {
                        onButtonEvent(type, code, value, device);
                    }
                }
            } catch (Exception e) {
                if (rootActive && running) {
                    LogBuffer.add("root 读取 " + device + " 失败: " + e);
                }
            } finally {
                if (process != null) {
                    synchronized (rootProcs) {
                        rootProcs.remove(process);
                    }
                    process.destroy();
                }
            }
            if (!running || !rootActive || !watcher.active) {
                break;
            }
            try {
                Thread.sleep(2000);
            } catch (InterruptedException ignored) {
            }
        }
        watcher.active = false;
    }

    private final Set<Integer> seenPenKeys = new HashSet<>();

    private void onButtonEvent(int type, int code, int value, String device) {
        if (type != 1) {
            return;
        }
        if (DEBUG_KEYS) {
            LogBuffer.add("evdev " + device + " key=" + code + " value=" + value);
        }
        boolean penKey = code == 78 || (code >= 183 && code <= 189);
        if (!penKey) {
            return;
        }
        if (value == 1 && seenPenKeys.add(code)) {
            LogBuffer.add("笔键 code=" + code + " (" + device + ")");
        }
        onRootKey(code, value);
    }

    private void onPressureEvent(int type, int code, int value, PressureState s) {
        if (type == 1 && code == 330) {
            s.touchDevice = true;
            if (value == 1) {
                s.touching = true;
                beginStroke(s);
            } else {
                boolean mine = s.expectInjectedUp;
                s.expectInjectedUp = false;
                s.lastUpAt = System.currentTimeMillis();
                if (!mine) {
                    s.touching = false;
                }
                if (s.strokeStart != 0) {
                    s.current = 0;
                    endStroke(s);
                }
            }
            return;
        }
        if (type == 0) {
            maybeFire(s);
            return;
        }
        if (type != 3) {
            return;
        }
        if (code == ABS_X) {
            s.lastX = value;
            markMove(s);
        } else if (code == ABS_Y) {
            s.lastY = value;
            markMove(s);
        } else if (code == ABS_PRESSURE) {
            if (s.touchDevice) {
                if (s.strokeStart == 0) {
                    return;
                }
            } else if (value > 0 && s.strokeStart == 0) {
                beginStroke(s);
            }
            s.current = value;
            if (value > s.max) {
                s.max = value;
            }
            if (value == 0) {
                endStroke(s);
            } else {
                maybeFire(s);
            }
        }
    }

    private void beginStroke(PressureState s) {
        long now = System.currentTimeMillis();
        strokeActiveSince = now;
        s.strokeStart = now;
        s.stillStart = s.strokeStart;
        s.anchorX = s.lastX;
        s.anchorY = s.lastY;
        s.max = 0;
        s.current = 0;
        s.fired = false;
        s.attempted = false;
        s.injected = now - s.injectDownAt < 400;
        if (!s.injected) {
            s.firedThisTouch = false;
        }
        PenAccessibilityService svc = PenAccessibilityService.INSTANCE;
        final String pkg = svc == null ? null : svc.foregroundPackage();
        final boolean huatu = ActionRunner.HUATU.equals(pkg);
        s.eraserStroke = huatuArmed && huatu;
        if (s.eraserStroke) {
            huatuArmed = false;
            cancelHuatuRestore();
            LogBuffer.add("橡皮笔画开始");
            // 画布在笔画开始时锁定工具，这里提前把工具栏切回画笔，
            // 抬笔后立刻就是画笔状态，不用多抬一次。
            scheduleRestore(s, 260);
        }
        handler.postDelayed(() -> maybeFire(s), HARD_PRESS_STILL_MS + 30);
    }

    private void scheduleRestore(PressureState s, long delayMs) {
        final int gen = ++s.restoreGen;
        s.restoreDone = false;
        handler.postDelayed(() -> {
            if (s.restoreGen == gen) {
                restorePen(0);
                s.restoreDone = true;
            }
        }, delayMs);
    }

    private void markMove(PressureState s) {
        if (s.strokeStart == 0) {
            return;
        }
        int dx = Math.abs(s.lastX - s.anchorX);
        int dy = Math.abs(s.lastY - s.anchorY);
        if (dx > 20 || dy > 20) {
            s.anchorX = s.lastX;
            s.anchorY = s.lastY;
            s.stillStart = System.currentTimeMillis();
        } else {
            maybeFire(s);
        }
    }

    private void maybeFire(PressureState s) {
        if (s.attempted || s.strokeStart == 0) {
            return;
        }
        if (s.injected || s.eraserStroke || s.firedThisTouch) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now < s.ignoreFireUntil) {
            return;
        }
        if (Prefs.eraserMode(this) != Prefs.MODE_PRESSURE) {
            return;
        }
        long still = now - s.stillStart;
        int threshold = Prefs.pressureLevel(this);
        if (s.current >= threshold && still >= HARD_PRESS_STILL_MS) {
            s.attempted = true;
            s.firedThisTouch = true;
            PenAccessibilityService svc = PenAccessibilityService.INSTANCE;
            String pkg = svc == null ? null : svc.foregroundPackage();
            if (ActionRunner.HUATU.equals(pkg)) {
                s.ignoreFireUntil = now + 1500;
                final String device = s.device;
                final int pressure = s.current;
                LogBuffer.add("重按笔尖 -> 断笔切橡皮 (压力 " + pressure + " 静止 " + still + "ms)");
                actionQueue.execute(() -> huatuEraserSwitch(s, device, pressure));
            } else {
                LogBuffer.add("重按笔尖 -> 橡皮 (压力 " + s.current + " 静止 " + still + "ms)");
                s.fired = rootFire(ActionRunner.GESTURE_LONG);
            }
        } else if (s.current >= threshold) {
            handler.postDelayed(() -> maybeFire(s), 40);
        }
    }

    /**
     * 华图的工具在笔画开始时锁定，笔画中途点击工具只对下一笔生效。
     * 因此重按后先注入一次抬笔断开当前笔画，切好橡皮再注入落笔，
     * 让用户接着擦的这一笔就是橡皮笔画。
     */
    private void huatuEraserSwitch(PressureState s, String device, int pressure) {
        PenAccessibilityService svc = PenAccessibilityService.INSTANCE;
        if (svc == null) {
            return;
        }
        String pkg = svc.foregroundPackage();
        if (!ActionRunner.HUATU.equals(pkg) || device == null) {
            return;
        }
        long t0 = System.currentTimeMillis();
        PenRelay r = relay;
        boolean viaRelay = r != null && r.isReady() && device != null && device.equals(relayNode);
        boolean wasDown = s.touching;
        // 先立刻抬笔切断当前这一笔，再去点工具栏，避免气泡等待期间笔画继续画。
        if (viaRelay && wasDown) {
            r.send("up");
        }
        boolean ok = ActionRunner.toggleEraser(svc, pkg, true);
        long tTool = System.currentTimeMillis() - t0;
        boolean down = false;
        if (ok) {
            huatuArmed = true;
            scheduleHuatuRestore();
        }
        if (viaRelay) {
            if (wasDown) {
                r.send("down");
                down = true;
            }
        } else if (ok && s.touching) {
            injectBreak(s, device, s.lastX, s.lastY, Math.max(pressure, 2500));
            down = true;
        }
        LogBuffer.add("华图切橡皮 " + (ok ? "成功" : "失败")
                + (down ? " + 断笔续画" : " (未落笔，等下一笔)")
                + " 工具" + tTool + "ms");
    }

    /** 同一帧内抬笔再落笔，让华图把当前笔画断成两段：后半段用新工具。 */
    private void injectBreak(PressureState s, String device, int x, int y, int pressure) {
        s.expectInjectedUp = true;
        s.injectDownAt = System.currentTimeMillis();
        long t = SystemClock.uptimeMillis();
        RootShell.run("sendevent " + device + " 3 24 0; "
                + "sendevent " + device + " 1 330 0; "
                + "sendevent " + device + " 3 25 1; "
                + "sendevent " + device + " 0 0 0; "
                + "sleep 0.045; "
                + "sendevent " + device + " 3 0 " + x + "; "
                + "sendevent " + device + " 3 1 " + y + "; "
                + "sendevent " + device + " 3 25 0; "
                + "sendevent " + device + " 3 24 " + pressure + "; "
                + "sendevent " + device + " 1 320 1; "
                + "sendevent " + device + " 1 330 1; "
                + "sendevent " + device + " 0 0 0");
        LogBuffer.add("注入断笔 at " + t + " x=" + x + " y=" + y + " p=" + pressure);
    }

    private static void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    private void endStroke(PressureState s) {
        long now = System.currentTimeMillis();
        lastStrokeEndAt = now;
        strokeActiveSince = 0;
        long dur = now - s.strokeStart;
        long stillMs = now - s.stillStart;
        int max = s.max;
        s.strokeStart = 0;
        s.max = 0;
        final boolean wasEraserStroke = s.eraserStroke;
        s.eraserStroke = false;
        if (max >= 6000) {
            LogBuffer.add("笔迹 压力=" + max + " 时长=" + dur + "ms 静止=" + stillMs + "ms");
        }
        if (wasEraserStroke) {
            LogBuffer.add("橡皮笔画结束");
            if (!s.restoreDone) {
                scheduleRestore(s, 40);
            }
        }
        if (s.fired) {
            s.fired = false;
            handler.postDelayed(() -> restorePen(0), 150);
        }
    }

    private void cancelHuatuRestore() {
        if (huatuRestore != null) {
            handler.removeCallbacks(huatuRestore);
            huatuRestore = null;
        }
    }

    private void scheduleHuatuRestore() {
        cancelHuatuRestore();
        huatuRestore = () -> {
            huatuRestore = null;
            if (strokeActiveSince != 0) {
                scheduleHuatuRestore();
                return;
            }
            if (System.currentTimeMillis() - lastStrokeEndAt < HUATU_ERASER_LINGER_MS - 500) {
                scheduleHuatuRestore();
                return;
            }
            huatuArmed = false;
            restorePen(0);
        };
        handler.postDelayed(huatuRestore, HUATU_ERASER_LINGER_MS);
    }

    private void restorePen(int attempt) {
        actionQueue.execute(() -> {
            PenAccessibilityService svc = PenAccessibilityService.INSTANCE;
            if (svc == null) {
                return;
            }
            String pkg = svc.foregroundPackage();
            if (!ActionRunner.isTarget(pkg)) {
                if (attempt < 3) {
                    handler.postDelayed(() -> restorePen(attempt + 1), 300);
                } else {
                    LogBuffer.add("抬笔 -> 画笔 跳过: 前台 " + pkg);
                }
                return;
            }
            boolean ok = ActionRunner.setPen(svc, pkg);
            LogBuffer.add("抬笔 -> 画笔 " + (ok ? "成功" : "未找到控件") + " (" + pkg + ")");
            if (!ok && attempt < 1) {
                handler.postDelayed(() -> restorePen(attempt + 1), 150);
            }
        });
    }

    private static int readFully(InputStream in, byte[] buf) throws Exception {
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n < 0) {
                return off;
            }
            off += n;
        }
        return off;
    }

    private static int le16(byte[] b, int off) {
        return (b[off] & 0xff) | ((b[off + 1] & 0xff) << 8);
    }

    private static int le32(byte[] b, int off) {
        return (b[off] & 0xff) | ((b[off + 1] & 0xff) << 8)
                | ((b[off + 2] & 0xff) << 16) | ((b[off + 3] & 0xff) << 24);
    }

    private void onRootKey(int code, int value) {
        long now = System.currentTimeMillis();
        if (value == 1) {
            rootDownAt = now;
            return;
        }
        if (value != 0) {
            return;
        }
        long held = rootDownAt == 0 ? 0 : now - rootDownAt;
        rootDownAt = 0;
        if (held <= 0) {
            return;
        }
        if (held >= LONG_PRESS_MS) {
            if (Prefs.eraserMode(this) == Prefs.MODE_LONG) {
                LogBuffer.add("笔键长按 " + held + "ms");
                rootFire(ActionRunner.GESTURE_LONG);
            }
            return;
        }
        if (held > MAX_CLICK_MS) {
            return;
        }
        rootClick(now);
    }

    private long rootDownAt;
    private long rootLastClickAt;
    private long rootLastFireAt;

    /** 单击笔键 = 草稿纸开关。450ms 内的连击只算一次，避免手抖双击又把它关掉。 */
    private void rootClick(long now) {
        if (now - rootLastClickAt < 450) {
            rootLastClickAt = now;
            return;
        }
        rootLastClickAt = now;
        rootFire(ActionRunner.GESTURE_DOUBLE);
    }

    private boolean rootFire(int gesture) {
        if (gesture == ActionRunner.GESTURE_SINGLE && Prefs.eraserMode(this) != Prefs.MODE_SINGLE) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (now - rootLastFireAt < 400) {
            return false;
        }
        rootLastFireAt = now;
        String name = gesture == ActionRunner.GESTURE_DOUBLE ? "单击(草稿纸)"
                : (gesture == ActionRunner.GESTURE_LONG ? "橡皮(重按/长按)" : "橡皮");
        LogBuffer.add("root 触发 " + name);
        actionQueue.execute(() -> ActionRunner.handle(gesture));
        return true;
    }

    public static void dumpToFile() {
        PenAccessibilityService svc = PenAccessibilityService.INSTANCE;
        if (svc == null) {
            LogBuffer.add("转储失败: 无障碍未启用");
            return;
        }
        String dump = svc.dumpAll();
        try {
            File dir = svc.getExternalFilesDir(null);
            File f = new File(dir, "dump.txt");
            java.io.FileOutputStream fos = new java.io.FileOutputStream(f);
            fos.write(dump.getBytes("UTF-8"));
            fos.close();
            LogBuffer.add("界面已转储: " + f + " (" + dump.length() + " 字符)");
        } catch (Exception e) {
            LogBuffer.add("写转储失败: " + e);
        }
    }

    @Override
    public void onDestroy() {
        running = false;
        sRunning = false;
        stopRelay();
        handler.removeCallbacksAndMessages(null);
        actionQueue.shutdownNow();
        LogBuffer.add("服务停止");
        super.onDestroy();
    }
}
