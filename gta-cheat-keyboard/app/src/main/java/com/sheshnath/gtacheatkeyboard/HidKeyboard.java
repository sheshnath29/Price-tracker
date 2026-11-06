package com.sheshnath.gtacheatkeyboard;

import java.io.OutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

import rikka.shizuku.Shizuku;

/**
 * A virtual USB keyboard created with Android's built-in "hid" shell tool
 * (the same one Android's own compatibility tests use). Games see it exactly
 * like a real plugged-in keyboard, unlike "input keyevent", whose events come
 * from a virtual device that many games ignore.
 *
 * The hid process stays running while the floating button is on; every key
 * press is a HID report written to its stdin.
 */
final class HidKeyboard {
    private HidKeyboard() {}

    /** Standard boot-protocol keyboard: 1 modifier byte, 1 reserved, 6 key slots. */
    private static final String DESCRIPTOR =
            "5,1,9,6,161,1,5,7,25,224,41,231,21,0,37,1,117,1,149,8,129,2,"
                    + "149,1,117,8,129,1,149,6,117,8,21,0,37,101,5,7,25,0,41,101,129,0,192";

    private static Process proc;
    private static OutputStream out;

    private static boolean alive() {
        if (proc == null) return false;
        try {
            proc.exitValue();
            return false;
        } catch (IllegalThreadStateException running) {
            return true;
        }
    }

    private static Process spawn(String cmd) throws Exception {
        if (KeyInjector.shizukuGranted()) {
            Method m = Shizuku.class.getDeclaredMethod(
                    "newProcess", String[].class, String[].class, String.class);
            m.setAccessible(true);
            return (Process) m.invoke(null, new String[]{"sh", "-c", cmd}, null, null);
        }
        return Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
    }

    /** Starts the virtual keyboard if needed. Blocking. Returns true if it is running. */
    static synchronized boolean ensure() {
        if (alive()) return true;
        stop();
        try {
            proc = spawn("exec hid -");
            out = proc.getOutputStream();
            write("{\"id\":1,\"command\":\"register\",\"name\":\"GTA Cheat Keyboard\","
                    + "\"vid\":4660,\"pid\":22136,\"bus\":\"usb\","
                    + "\"descriptor\":[" + DESCRIPTOR + "]}\n");
            // Give Android time to add the device before the first key press.
            Thread.sleep(1500);
            if (!alive()) {
                stop();
                return false;
            }
            return true;
        } catch (Throwable t) {
            stop();
            return false;
        }
    }

    /**
     * Presses each HID usage (4 = A ... 29 = Z, 40 = Enter, 42 = Backspace, 44 = Space)
     * for holdMs, with gapMs between keys. Blocking until the keys are typed.
     */
    static synchronized boolean press(int[] usages, int holdMs, int gapMs) {
        if (!ensure()) return false;
        try {
            for (int u : usages) {
                write("{\"id\":1,\"command\":\"report\",\"report\":[0,0," + u + ",0,0,0,0,0]}\n");
                Thread.sleep(holdMs);
                write("{\"id\":1,\"command\":\"report\",\"report\":[0,0,0,0,0,0,0,0]}\n");
                Thread.sleep(gapMs);
            }
            return alive();
        } catch (Throwable t) {
            stop();
            return false;
        }
    }

    private static void write(String s) throws Exception {
        out.write(s.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    /** Removes the virtual keyboard. */
    static synchronized void stop() {
        try {
            if (out != null) out.close();
        } catch (Throwable ignored) {
        }
        if (proc != null) proc.destroy();
        out = null;
        proc = null;
    }
}
