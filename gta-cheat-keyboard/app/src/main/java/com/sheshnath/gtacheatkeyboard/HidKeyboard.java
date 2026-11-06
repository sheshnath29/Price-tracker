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

    /** Last output from the hid tool, shown to the user when it fails. */
    static volatile String lastError = "";
    private static final StringBuilder output = new StringBuilder();

    private static boolean alive() {
        if (proc == null) return false;
        try {
            return proc.isAlive();
        } catch (IllegalArgumentException running) {
            // Shizuku's remote process reports "still running" (IllegalThreadStateException)
            // over Binder, which arrives here as a plain IllegalArgumentException.
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Keeps reading the tool's output so its pipe never fills; remembers the tail. */
    private static void drain(final Process p) {
        Thread t = new Thread(() -> {
            byte[] buf = new byte[512];
            try (java.io.InputStream in = p.getInputStream()) {
                int n;
                while ((n = in.read(buf)) > 0) {
                    synchronized (output) {
                        output.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                        if (output.length() > 400) output.delete(0, output.length() - 400);
                    }
                }
            } catch (Throwable ignored) {
            }
        });
        t.setDaemon(true);
        t.start();
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
            synchronized (output) {
                output.setLength(0);
            }
            proc = spawn("command -v hid >/dev/null || { echo 'hid tool not found'; exit 127; }; "
                    + "exec hid - 2>&1");
            drain(proc);
            out = proc.getOutputStream();
            write("{\"id\":1,\"command\":\"register\",\"name\":\"GTA Cheat Keyboard\","
                    + "\"vid\":4660,\"pid\":22136,\"bus\":\"usb\","
                    + "\"descriptor\":[" + DESCRIPTOR + "]}\n");
            // Give Android time to add the device before the first key press.
            Thread.sleep(1500);
            if (!alive()) {
                Thread.sleep(200); // let the last output arrive
                synchronized (output) {
                    lastError = output.toString().trim();
                }
                if (lastError.isEmpty()) lastError = "hid tool exited";
                stop();
                return false;
            }
            lastError = "";
            return true;
        } catch (Throwable t) {
            lastError = t.getClass().getSimpleName() + ": " + t.getMessage();
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
