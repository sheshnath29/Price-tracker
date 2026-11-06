package com.sheshnath.gtacheatkeyboard;

import android.content.pm.PackageManager;
import android.view.KeyEvent;

import java.lang.reflect.Method;

import rikka.shizuku.Shizuku;

/**
 * Sends key presses to whatever app is in front (the game) using the shell
 * "input keyevent" command. Needs Shizuku (no root) or root, because normal
 * apps are not allowed to send keys into other apps.
 */
final class KeyInjector {
    private KeyInjector() {}

    static boolean shizukuRunning() {
        try {
            return Shizuku.pingBinder();
        } catch (Throwable t) {
            return false;
        }
    }

    static boolean shizukuGranted() {
        try {
            return shizukuRunning()
                    && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Builds "input keyevent 36 33 ..." for an A-Z code. */
    static String commandFor(String code) {
        StringBuilder sb = new StringBuilder("input keyevent");
        for (char c : code.toUpperCase().toCharArray()) {
            if (c >= 'A' && c <= 'Z') {
                sb.append(' ').append(KeyEvent.KEYCODE_A + (c - 'A'));
            }
        }
        return sb.toString();
    }

    /**
     * Types the code into the foreground app. Blocking - call off the main thread.
     * Returns null on success, otherwise an error message.
     */
    static String type(String code) {
        String cmd = commandFor(code);
        if (shizukuGranted()) {
            try {
                Method m = Shizuku.class.getDeclaredMethod(
                        "newProcess", String[].class, String[].class, String.class);
                m.setAccessible(true);
                Process p = (Process) m.invoke(null,
                        new String[]{"sh", "-c", cmd}, null, null);
                int exit = p.waitFor();
                return exit == 0 ? null : "Shizuku command failed (" + exit + ")";
            } catch (Throwable t) {
                return "Shizuku error: " + t.getClass().getSimpleName();
            }
        }
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            int exit = p.waitFor();
            if (exit == 0) return null;
        } catch (Throwable ignored) {
            // No root either.
        }
        return "Shizuku not connected - open the app and tap \"Connect Shizuku\"";
    }
}
