package com.sheshnath.gtacheatkeyboard;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import rikka.shizuku.Shizuku;

/** Setup screen for the floating cheat button (in-game) and the keyboard. */
public class MainActivity extends Activity {

    private static final int SHIZUKU_REQUEST = 7;
    private static final String SHIZUKU_PLAY =
            "https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api";

    private TextView status;
    private int pad;

    private final Shizuku.OnRequestPermissionResultListener shizukuListener =
            (requestCode, grantResult) -> runOnUiThread(this::refresh);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        pad = Math.round(16 * getResources().getDisplayMetrics().density);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(pad, pad, pad, pad);

        layout.addView(heading("GTA SA Cheat Keyboard", 24));

        layout.addView(heading("A. Floating cheat button (use this in the game)", 18));
        layout.addView(text(
                "GTA does not open a keyboard while you play, so use this floating button. "
                        + "It sends the key presses straight to the game.\n\n"
                        + "One-time setup:\n"
                        + "1. Install Shizuku from the Play Store and start it "
                        + "(Shizuku → \"Start via Wireless debugging\" → follow its steps).\n"
                        + "2. Tap \"Connect Shizuku\" below and choose Allow.\n"
                        + "3. Tap \"Allow floating button\" and turn it on for this app.\n"
                        + "4. Tap \"Start floating button\", then open GTA San Andreas.\n"
                        + "5. In the game, tap the green GTA bubble. A keyboard hovers over the "
                        + "game - type any cheat letter by letter, or use the CHEATS tab for "
                        + "one-tap cheats.\n\n"
                        + "After a phone restart, start Shizuku again first."));

        status = text("");
        status.setTextColor(Color.parseColor("#FFD54F"));
        layout.addView(status);

        layout.addView(button("Get Shizuku (Play Store)", () ->
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(SHIZUKU_PLAY)))));
        layout.addView(button("Connect Shizuku", this::connectShizuku));
        layout.addView(button("Allow floating button", () ->
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())))));
        layout.addView(button("Start floating button", this::startFloating));
        layout.addView(button("Stop floating button", () ->
                startService(new Intent(this, FloatingCheatService.class)
                        .setAction(FloatingCheatService.ACTION_STOP))));

        layout.addView(heading("B. Keyboard (for text boxes / cheat mods)", 18));
        layout.addView(text("Works only where a text box opens a keyboard."));
        layout.addView(button("Enable keyboard", () ->
                startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))));
        layout.addView(button("Choose keyboard", () ->
                ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
                        .showInputMethodPicker()));
        EditText test = new EditText(this);
        test.setHint("Test box - tap here and press a cheat");
        layout.addView(test);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(layout);
        setContentView(scroll);

        try {
            Shizuku.addRequestPermissionResultListener(shizukuListener);
        } catch (Throwable ignored) {
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    protected void onDestroy() {
        try {
            Shizuku.removeRequestPermissionResultListener(shizukuListener);
        } catch (Throwable ignored) {
        }
        super.onDestroy();
    }

    private void refresh() {
        String shizuku = !KeyInjector.shizukuRunning() ? "not running ✗"
                : KeyInjector.shizukuGranted() ? "connected ✓" : "running, not allowed yet ✗";
        String overlay = Settings.canDrawOverlays(this) ? "allowed ✓" : "not allowed ✗";
        String floating = FloatingCheatService.running ? "on ✓" : "off";
        status.setText("Shizuku: " + shizuku + "\nFloating permission: " + overlay
                + "\nFloating button: " + floating);
    }

    private void connectShizuku() {
        if (!KeyInjector.shizukuRunning()) {
            Toast.makeText(this, "Open Shizuku and start it first", Toast.LENGTH_LONG).show();
            return;
        }
        if (KeyInjector.shizukuGranted()) {
            Toast.makeText(this, "Already connected", Toast.LENGTH_SHORT).show();
        } else {
            Shizuku.requestPermission(SHIZUKU_REQUEST);
        }
    }

    private void startFloating() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Tap \"Allow floating button\" first", Toast.LENGTH_LONG).show();
            return;
        }
        if (!KeyInjector.shizukuGranted()) {
            Toast.makeText(this, "Shizuku isn't connected yet - cheats won't send until it is",
                    Toast.LENGTH_LONG).show();
        }
        startForegroundService(new Intent(this, FloatingCheatService.class));
        status.postDelayed(this::refresh, 500);
    }

    private TextView heading(String s, int size) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(Color.parseColor("#66BB6A"));
        t.setPadding(0, pad, 0, pad / 4);
        return t;
    }

    private TextView text(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(15);
        t.setPadding(0, pad / 4, 0, pad / 4);
        return t;
    }

    private Button button(String label, Runnable action) {
        Button b = new Button(this);
        b.setText(label);
        b.setOnClickListener(v -> action.run());
        return b;
    }
}
