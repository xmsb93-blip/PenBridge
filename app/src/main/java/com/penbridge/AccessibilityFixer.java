package com.penbridge;

import android.content.Context;

public final class AccessibilityFixer {
    private AccessibilityFixer() {
    }

    public static void repair(Context context) {
        String pkg = context.getPackageName();
        String svc = pkg + "/" + PenAccessibilityService.class.getName();
        String db = "/data/data/com.miui.powerkeeper/databases/user_configure.db";
        String cmd = "cur=$(settings get secure enabled_accessibility_services); "
                + "case \"$cur\" in *" + pkg + "*) ;; "
                + "null|'') settings put secure enabled_accessibility_services " + svc + " ;; "
                + "*) settings put secure enabled_accessibility_services \"$cur:" + svc + "\" ;; esac; "
                + "settings put secure accessibility_enabled 1; "
                + "dumpsys deviceidle whitelist +" + pkg + "; "
                + "bg=$(sqlite3 " + db + " \"SELECT bgControl FROM userTable WHERE pkgName='" + pkg + "';\" 2>/dev/null); "
                + "if [ \"$bg\" != \"noRestrict\" ]; then "
                + "sqlite3 " + db + " \"INSERT OR REPLACE INTO userTable(userId,pkgName,lastConfigured,bgControl) "
                + "VALUES(0,'" + pkg + "'," + System.currentTimeMillis() + ",'noRestrict');\" 2>/dev/null; "
                + "kill $(pidof com.miui.powerkeeper) 2>/dev/null; "
                + "echo powerkeeper=noRestrict; fi";
        String out = RootShell.run(cmd);
        LogBuffer.add("无障碍修复: " + out.trim() + " (当前: "
                + RootShell.run("settings get secure enabled_accessibility_services").trim() + ")");
    }
}
