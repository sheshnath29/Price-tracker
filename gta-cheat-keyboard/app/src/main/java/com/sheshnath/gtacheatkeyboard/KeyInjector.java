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

    /** Typing speeds: {name, ms each key is held down, ms pause between keys}. */
    static final Object[][] SPEEDS = {
            {"Fast", 60, 60},
            {"Normal", 120, 150},
            {"Slow", 200, 300},
            {"Very slow", 300, 600},
    };
    /** Index into SPEEDS; set from the floating keyboard's Speed button. */
    static volatile int speed = 2;

    /**
     * Builds the shell command for an A-Z code.
     *
     * A plain "input keyevent" releases the key ~1 ms after pressing it. The game
     * polls the keyboard once per frame, so such a tap falls between two frames
     * and is never seen. "--duration" holds each key down like a real finger
     * (Android 13+); older Android falls back to one key per command with pauses.
     */
    static String commandFor(String code) {
        StringBuilder keys = new StringBuilder();
        for (char c : code.toUpperCase().toCharArray()) {
            if (c >= 'A' && c <= 'Z') {
                keys.append(' ').append(KeyEvent.KEYCODE_A + (c - 'A'));
            }
        }
        return commandForKeys(keys.toString());
    }

    /** k is a space-prefixed list of key codes, e.g. " 36 33". */
    private static String commandForKeys(String k) {
        int hold = (Integer) SPEEDS[speed][1];
        String gap = String.format(java.util.Locale.US, "%.2f", (Integer) SPEEDS[speed][2] / 1000.0);
        // One key per command so each press is clearly separated; hold it down with
        // --duration (Android 13+), falling back to a plain press if unsupported.
        return "for k in" + k + "; do input keyboard keyevent --duration " + hold
                + " $k 2>/dev/null || input keyboard keyevent $k; sleep " + gap + "; done";
    }

    /**
     * Types the code into the foreground app. Blocking - call off the main thread.
     * Returns null on success, otherwise an error message.
     */
    static String type(String code) {
        return run(commandFor(code));
    }

    /** Presses one key by key code (e.g. ENTER, BACKSPACE). Blocking. */
    static String pressKey(int keyCode) {
        return run(commandForKeys(" " + keyCode));
    }

    private static String run(String cmd) {
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
