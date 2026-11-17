package com.penbridge;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.HashMap;
import java.util.Map;

public final class ActionRunner {
    public static final String FENBI = "com.fenbi.android.servant";
    public static final String HUATU = "com.huatu.handheld_huatu";

    public static final int GESTURE_SINGLE = 1;
    public static final int GESTURE_DOUBLE = 2;
    public static final int GESTURE_LONG = 3;
    public static final int GESTURE_PEN = 4;

    private static final Map<String, Long> OPEN_STATE = new HashMap<>();
    private static final Map<String, Boolean> ERASER_STATE = new HashMap<>();
    private static final long STATE_TTL_MS = 10 * 60 * 1000L;
    private static volatile long lastHandleAt;

    private ActionRunner() {
    }

    public static boolean isTarget(String pkg) {
        return FENBI.equals(pkg) || HUATU.equals(pkg);
    }

    public static String handle(int gesture) {
        long now = System.currentTimeMillis();
        if (now - lastHandleAt < 500) {
            return "忽略重复手势";
        }
        lastHandleAt = now;
        PenAccessibilityService svc = PenAccessibilityService.INSTANCE;
        if (svc == null) {
            return "无障碍服务未启用";
        }
        String pkg = svc.foregroundPackage();
        if (pkg == null) {
            return "无法获取前台应用";
        }
        if (!isTarget(pkg)) {
            return "前台不是粉笔/华图: " + pkg;
        }
        boolean scratch = gesture == GESTURE_DOUBLE;
        boolean ok = scratch ? toggleScratch(svc, pkg) : toggleEraser(svc, pkg, gesture != GESTURE_SINGLE);
        String name = scratch ? "草稿纸" : (gesture == GESTURE_LONG ? "橡皮(长按)" : "橡皮");
        return name + " -> " + (ok ? "成功" : "未找到控件");
    }

    public static boolean toggleScratch(PenAccessibilityService svc, String pkg) {
        boolean visible;
        boolean entryVisible;
        if (FENBI.equals(pkg)) {
            visible = svc.exists(pkg, "scratch_bar", "scratch_close", "scratch_erase", "scroll_scratch");
            entryVisible = svc.exists(pkg, "question_bar_scratch", "collapsed_scratch");
        } else {
            visible = svc.exists(pkg, "clTopBackground", "ivToggleView", "ivClose");
            entryVisible = svc.exists(pkg, "ivDraft");
        }
        Long since = OPEN_STATE.get(pkg);
        boolean rememberedOpen = since != null && System.currentTimeMillis() - since < STATE_TTL_MS;
        boolean open = visible || (rememberedOpen && !entryVisible);
        LogBuffer.add("草稿纸当前状态: " + (open ? "打开" : "关闭"));
        boolean ok;
        if (open) {
            if (FENBI.equals(pkg)) {
                ok = svc.clickAny(pkg, "scratch_close", "scratch_delete");
            } else {
                ok = svc.clickAny(pkg, "ivClose");
            }
            if (!ok) {
                boolean rootVisible = FENBI.equals(pkg)
                        ? svc.exists(pkg, "scroll_scratch")
                        : svc.exists(pkg, "clTopBackground");
                if (rootVisible) {
                    svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
                    ok = true;
                    LogBuffer.add("未找到关闭按钮，对草稿层执行返回");
                } else {
                    LogBuffer.add("未找到关闭按钮，忽略本次操作");
                }
            }
        } else if (FENBI.equals(pkg)) {
            ok = svc.clickAny(pkg, "question_bar_scratch", "collapsed_scratch");
            if (!ok) {
                AccessibilityNodeInfo n = svc.findByExactText(pkg, "草稿", "草稿纸");
                ok = n != null && svc.click(n);
            }
        } else {
            ok = svc.clickAny(pkg, "ivDraft");
            if (!ok) {
                AccessibilityNodeInfo n = svc.findByExactText(pkg, "草稿", "草稿纸");
                ok = n != null && svc.click(n);
            }
        }
        if (ok) {
            if (open) {
                OPEN_STATE.remove(pkg);
            } else {
                OPEN_STATE.put(pkg, System.currentTimeMillis());
                ERASER_STATE.put(pkg, false);
            }
        }
        return ok;
    }

    public static boolean toggleEraser(PenAccessibilityService svc, String pkg, boolean forceOn) {
        boolean eraserOn = Boolean.TRUE.equals(ERASER_STATE.get(pkg));
        if (forceOn && eraserOn && FENBI.equals(pkg)) {
            LogBuffer.add("已在橡皮模式，忽略");
            return true;
        }
        boolean wantEraser = forceOn || !eraserOn;
        boolean ok;
        if (FENBI.equals(pkg)) {
            if (wantEraser) {
                ok = svc.clickAny(pkg, "scratch_erase");
            } else {
                ok = svc.clickAny(pkg, "scratch_stroke_width");
            }
        } else {
            ok = clickTool(svc, pkg, wantEraser ? "ivEraser" : "ivPen");
            if (ok) {
                ERASER_STATE.put(pkg, wantEraser);
            }
        }
        if (!ok) {
            AccessibilityNodeInfo n = svc.findByExactText(pkg, "橡皮", "橡皮擦");
            ok = n != null && svc.click(n);
        }
        if (ok) {
            ERASER_STATE.put(pkg, wantEraser);
        }
        return ok;
    }

    public static boolean setPen(PenAccessibilityService svc, String pkg) {
        boolean ok;
        if (FENBI.equals(pkg)) {
            ok = svc.clickAny(pkg, "scratch_stroke_width");
        } else {
            ok = clickTool(svc, pkg, "ivPen");
            if (ok) {
                ERASER_STATE.put(pkg, false);
            }
        }
        if (ok) {
            ERASER_STATE.put(pkg, false);
        }
        if (!ok) {
            AccessibilityNodeInfo n = svc.findByExactText(pkg, "画笔", "钢笔");
            ok = n != null && svc.click(n);
        }
        return ok;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    public static boolean clickTool(PenAccessibilityService svc, String pkg, String id) {
        AccessibilityNodeInfo n = svc.findById(pkg, id);
        if (n == null) {
            return false;
        }
        long t0 = System.currentTimeMillis();
        boolean ok = svc.clickExact(n);
        LogBuffer.add("点击工具 " + id + " -> " + ok
                + " (" + (System.currentTimeMillis() - t0) + "ms)");
        if (ok) {
            closeStrokePanel(svc, pkg, 400);
        }
        return ok;
    }

    /** 华图点击画笔/橡皮后会弹出「粗细」气泡，气泡会吞掉下一次落笔，必须关掉。 */
    private static void closeStrokePanel(PenAccessibilityService svc, String pkg, long maxWaitMs) {
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < maxWaitMs) {
            if (svc.findByText(pkg, "粗细") != null) {
                long seen = System.currentTimeMillis() - start;
                svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
                LogBuffer.add("关闭粗细弹窗 (出现于 +" + seen + "ms)");
                sleep(40);
                return;
            }
            sleep(20);
        }
        LogBuffer.add("粗细弹窗 " + maxWaitMs + "ms 内未出现");
    }
}
