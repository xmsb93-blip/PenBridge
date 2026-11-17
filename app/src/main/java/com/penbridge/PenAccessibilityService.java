package com.penbridge;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class PenAccessibilityService extends AccessibilityService {
    public static volatile PenAccessibilityService INSTANCE;
    public static volatile boolean DEBUG_KEYS;

    private static final long MAX_CLICK_MS = 900;

    private volatile String lastPackage;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Set<Integer> seenKeys = new HashSet<>();
    private long downAt;
    private long lastClickAt;
    private long lastActionAt;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        INSTANCE = this;
        LogBuffer.add("无障碍服务已连接");
        try {
            startForegroundService(new Intent(this, PenService.class));
        } catch (Exception e) {
            LogBuffer.add("自动启动笔控服务失败: " + e);
        }
    }

    @Override
    public void onDestroy() {
        INSTANCE = null;
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && event.getPackageName() != null) {
            lastPackage = event.getPackageName().toString();
        }
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public boolean onKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        if (DEBUG_KEYS) {
            String dev = event.getDevice() == null ? "?" : event.getDevice().getName();
            LogBuffer.add("KEY " + KeyEvent.keyCodeToString(code)
                    + " action=" + event.getAction() + " scan=" + event.getScanCode() + " dev=" + dev);
        }
        boolean interesting = code == KeyEvent.KEYCODE_NUMPAD_ADD
                || (code >= KeyEvent.KEYCODE_F13 && code <= KeyEvent.KEYCODE_F19);
        if (!interesting) {
            return false;
        }
        if (seenKeys.add(code)) {
            LogBuffer.add("检测到笔按键 keyCode=" + code
                    + " (" + KeyEvent.keyCodeToString(code) + ") dev="
                    + (event.getDevice() == null ? "?" : event.getDevice().getName()));
        }
        long now = System.currentTimeMillis();
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (event.getRepeatCount() == 0) {
                downAt = now;
            }
        } else if (event.getAction() == KeyEvent.ACTION_UP) {
            long held = downAt == 0 ? 0 : now - downAt;
            if (downAt == 0 || held > MAX_CLICK_MS) {
                downAt = 0;
                return false;
            }
            downAt = 0;
            if (held >= 600) {
                if (Prefs.eraserMode(this) == Prefs.MODE_LONG) {
                    LogBuffer.add("笔键长按 " + held + "ms");
                    fire(ActionRunner.GESTURE_LONG);
                }
                return false;
            }
            onClick(now);
        }
        return false;
    }

    /** 单击笔键 = 草稿纸开关。450ms 内的连击只算一次。 */
    private void onClick(long now) {
        if (now - lastClickAt < 450) {
            lastClickAt = now;
            return;
        }
        lastClickAt = now;
        fire(ActionRunner.GESTURE_DOUBLE);
    }

    private void fire(int gesture) {
        if (gesture == ActionRunner.GESTURE_SINGLE
                && Prefs.eraserMode(this) != Prefs.MODE_SINGLE) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastActionAt < 400) {
            LogBuffer.add("忽略重复手势");
            return;
        }
        lastActionAt = now;
        String result = ActionRunner.handle(gesture);
        String name = gesture == ActionRunner.GESTURE_DOUBLE ? "单击(草稿纸)"
                : (gesture == ActionRunner.GESTURE_LONG ? "橡皮(长按)" : "橡皮");
        LogBuffer.add(name + ": " + result);
    }

    public String foregroundPackage() {
        AccessibilityWindowInfo fallback = null;
        try {
            for (AccessibilityWindowInfo window : getWindows()) {
                if (window.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) {
                    continue;
                }
                AccessibilityNodeInfo root = window.getRoot();
                if (root == null || root.getPackageName() == null) {
                    continue;
                }
                if (window.isFocused()) {
                    return root.getPackageName().toString();
                }
                if (fallback == null || window.getLayer() > fallback.getLayer()) {
                    fallback = window;
                }
            }
            if (fallback != null) {
                AccessibilityNodeInfo root = fallback.getRoot();
                if (root != null && root.getPackageName() != null) {
                    return root.getPackageName().toString();
                }
            }
        } catch (Exception e) {
            LogBuffer.add("取前台窗口失败: " + e);
        }
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null && root.getPackageName() != null) {
            return root.getPackageName().toString();
        }
        return lastPackage;
    }

    private List<AccessibilityNodeInfo> rootsFor(String pkg) {
        List<AccessibilityNodeInfo> roots = new ArrayList<>();
        try {
            for (AccessibilityWindowInfo window : getWindows()) {
                AccessibilityNodeInfo root = window.getRoot();
                if (root == null || root.getPackageName() == null) {
                    continue;
                }
                if (root.getPackageName().toString().equals(pkg)) {
                    roots.add(root);
                }
            }
        } catch (Exception e) {
            LogBuffer.add("枚举窗口失败: " + e);
        }
        if (roots.isEmpty()) {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null && root.getPackageName() != null
                    && root.getPackageName().toString().equals(pkg)) {
                roots.add(root);
            }
        }
        return roots;
    }

    public AccessibilityNodeInfo findById(String pkg, String... ids) {
        for (String id : ids) {
            for (AccessibilityNodeInfo root : rootsFor(pkg)) {
                AccessibilityNodeInfo node = findById(root, id);
                if (node != null) {
                    return node;
                }
            }
        }
        return null;
    }

    private AccessibilityNodeInfo findById(AccessibilityNodeInfo node, String id) {
        if (node == null) {
            return null;
        }
        String viewId = node.getViewIdResourceName();
        if (viewId != null && node.isVisibleToUser()
                && (viewId.equals(id) || viewId.endsWith("/" + id))) {
            return node;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo found = findById(node.getChild(i), id);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    public AccessibilityNodeInfo findByText(String pkg, String... needles) {
        for (AccessibilityNodeInfo root : rootsFor(pkg)) {
            AccessibilityNodeInfo node = findByText(root, needles);
            if (node != null) {
                return node;
            }
        }
        return null;
    }

    public AccessibilityNodeInfo findByExactText(String pkg, String... texts) {
        for (AccessibilityNodeInfo root : rootsFor(pkg)) {
            AccessibilityNodeInfo node = findByExactText(root, texts);
            if (node != null) {
                return node;
            }
        }
        return null;
    }

    private AccessibilityNodeInfo findByExactText(AccessibilityNodeInfo node, String[] texts) {
        if (node == null) {
            return null;
        }
        if (node.isVisibleToUser()) {
            CharSequence text = node.getText();
            CharSequence desc = node.getContentDescription();
            for (String want : texts) {
                if ((text != null && want.contentEquals(text.toString().trim()))
                        || (desc != null && want.contentEquals(desc.toString().trim()))) {
                    return node;
                }
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo found = findByExactText(node.getChild(i), texts);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private AccessibilityNodeInfo findByText(AccessibilityNodeInfo node, String[] needles) {
        if (node == null) {
            return null;
        }
        if (node.isVisibleToUser()) {
            String text = join(node.getText(), node.getContentDescription());
            for (String needle : needles) {
                if (text.contains(needle)) {
                    return node;
                }
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo found = findByText(node.getChild(i), needles);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static String join(CharSequence a, CharSequence b) {
        StringBuilder sb = new StringBuilder();
        if (a != null) sb.append(a);
        if (b != null) sb.append(' ').append(b);
        return sb.toString();
    }

    public boolean exists(String pkg, String... ids) {
        return findById(pkg, ids) != null;
    }

    public boolean click(AccessibilityNodeInfo node) {
        if (node == null) {
            return false;
        }
        AccessibilityNodeInfo cur = node;
        while (cur != null) {
            if (cur.isClickable() && cur.isEnabled()
                    && cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true;
            }
            cur = cur.getParent();
        }
        Rect r = new Rect();
        node.getBoundsInScreen(r);
        if (r.width() > 0 && r.height() > 0) {
            return tap(r.centerX(), r.centerY());
        }
        return false;
    }

    public boolean clickAny(String pkg, String... ids) {
        AccessibilityNodeInfo node = findById(pkg, ids);
        if (node != null && click(node)) {
            LogBuffer.add("点击 id=" + node.getViewIdResourceName());
            return true;
        }
        return false;
    }

    public boolean clickExact(AccessibilityNodeInfo node) {
        return node != null && node.isEnabled()
                && node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
    }

    public boolean tapNode(AccessibilityNodeInfo node) {
        if (node == null) {
            return false;
        }
        Rect r = new Rect();
        node.getBoundsInScreen(r);
        if (r.width() <= 0 || r.height() <= 0) {
            return false;
        }
        LogBuffer.add("触摸 id=" + node.getViewIdResourceName()
                + " (" + r.centerX() + "," + r.centerY() + ")");
        return tap(r.centerX(), r.centerY());
    }

    public boolean tap(int x, int y) {
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, 60))
                .build();
        boolean ok = dispatchGesture(gesture, null, null);
        LogBuffer.add("手势点击 (" + x + "," + y + ") -> " + ok);
        return ok;
    }

    public String dumpAll() {
        StringBuilder sb = new StringBuilder();
        try {
            for (AccessibilityWindowInfo window : getWindows()) {
                AccessibilityNodeInfo root = window.getRoot();
                if (root == null) {
                    continue;
                }
                sb.append("=== window type=").append(window.getType())
                        .append(" layer=").append(window.getLayer()).append('\n');
                walk(root, 0, sb);
            }
        } catch (Exception e) {
            sb.append("dump error: ").append(e).append('\n');
        }
        return sb.toString();
    }

    private void walk(AccessibilityNodeInfo node, int depth, StringBuilder sb) {
        if (node == null || depth > 40) {
            return;
        }
        Rect r = new Rect();
        node.getBoundsInScreen(r);
        CharSequence text = node.getText();
        CharSequence desc = node.getContentDescription();
        sb.append(indent(depth))
                .append(node.getPackageName()).append(' ')
                .append(node.getClassName())
                .append(" id=").append(node.getViewIdResourceName())
                .append(" text=").append(trim(text))
                .append(" desc=").append(trim(desc))
                .append(" bounds=").append(r)
                .append(node.isClickable() ? " clickable" : "")
                .append(node.isSelected() ? " selected" : "")
                .append(node.isVisibleToUser() ? "" : " invisible")
                .append('\n');
        for (int i = 0; i < node.getChildCount(); i++) {
            walk(node.getChild(i), depth + 1, sb);
        }
    }

    private static String indent(int depth) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < depth; i++) {
            sb.append("  ");
        }
        return sb.toString();
    }

    private static String trim(CharSequence cs) {
        if (cs == null) {
            return "";
        }
        String s = cs.toString().replace('\n', ' ');
        return s.length() > 80 ? s.substring(0, 80) + "..." : s;
    }
}
