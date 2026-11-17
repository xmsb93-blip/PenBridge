package com.penbridge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

public class CommandReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String expected = Prefs.commandToken(context);
        if (expected == null || !expected.equals(intent.getStringExtra("token"))) {
            return;
        }
        String cmd = intent.getStringExtra("cmd");
        if (cmd == null) {
            return;
        }
        switch (cmd) {
            case "start":
                context.startForegroundService(new Intent(context, PenService.class));
                break;
            case "stop":
                context.stopService(new Intent(context, PenService.class));
                break;
            case "dump":
                PenService.dumpToFile();
                break;
            case "scratch":
                toast(context, ActionRunner.handle(ActionRunner.GESTURE_DOUBLE));
                break;
            case "eraser":
                toast(context, ActionRunner.handle(ActionRunner.GESTURE_SINGLE));
                break;
            case "status":
                toast(context, "acc=" + (PenAccessibilityService.INSTANCE != null)
                        + " svc=" + PenService.isRunning());
                break;
            case "keys":
                PenAccessibilityService.DEBUG_KEYS = !PenAccessibilityService.DEBUG_KEYS;
                PenService.DEBUG_KEYS = PenAccessibilityService.DEBUG_KEYS;
                toast(context, "按键调试: " + (PenAccessibilityService.DEBUG_KEYS ? "开" : "关"));
                break;
            case "eraser_mode":
                Prefs.cycleEraserMode(context);
                toast(context, "橡皮触发: " + Prefs.eraserModeName(context));
                break;
            case "pressure":
                Prefs.cyclePressureLevel(context);
                toast(context, "重按阈值: " + Prefs.pressureLevel(context));
                break;
            case "clickid": {
                String id = intent.getStringExtra("id");
                PenAccessibilityService svc = PenAccessibilityService.INSTANCE;
                if (svc == null || id == null) {
                    LogBuffer.add("CMD clickid 跳过: svc=" + (svc != null) + " id=" + id);
                    break;
                }
                String pkg = svc.foregroundPackage();
                boolean ok = pkg != null && ActionRunner.clickTool(svc, pkg, id);
                LogBuffer.add("CMD clickid " + id + " -> " + ok + " (" + pkg + ")");
                break;
            }
            default:
                break;
        }
        LogBuffer.add("CMD " + cmd);
    }

    private static void toast(Context c, String s) {
        Toast.makeText(c, s, Toast.LENGTH_SHORT).show();
    }
}
