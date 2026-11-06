package com.sheshnath.gtacheatkeyboard;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Floating "GTA" bubble drawn over the game. Tap it to open the cheat list;
 * tapping a cheat types it into the game. The overlay never takes focus, so
 * the key presses go to the game.
 */
public class FloatingCheatService extends Service {

    static final String ACTION_STOP = "com.sheshnath.gtacheatkeyboard.STOP";
    private static final String CHANNEL = "floating";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private WindowManager wm;
    private LinearLayout root;
    private View panel;
    private WindowManager.LayoutParams params;

    static boolean running;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        startInForeground();
        if (root == null) {
            showOverlay();
            // Connect the virtual USB keyboard now so the first cheat is instant.
            worker.execute(() -> {
                boolean ok = HidKeyboard.ensure();
                main.post(() -> Toast.makeText(this, ok
                        ? "Virtual USB keyboard connected ✓"
                        : "Virtual USB keyboard unavailable: " + HidKeyboard.lastError,
                        Toast.LENGTH_LONG).show());
            });
        }
        running = true;
        return START_STICKY;
    }

    private void startInForeground() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(
                CHANNEL, "Floating cheat button", NotificationManager.IMPORTANCE_LOW));
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, FloatingCheatService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_edit)
                .setContentTitle("GTA cheat button is on")
                .setContentText("Tap the floating GTA button in the game")
                .addAction(new Notification.Action.Builder(null, "Stop", stop).build())
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(1, n);
        }
    }

    private void showOverlay() {
        wm = getSystemService(WindowManager.class);

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        TextView bubble = new TextView(this);
        bubble.setText("GTA");
        bubble.setTextColor(Color.WHITE);
        bubble.setTypeface(Typeface.DEFAULT_BOLD);
        bubble.setGravity(Gravity.CENTER);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(Color.parseColor("#CC2E7D32"));
        circle.setStroke(dp(2), Color.WHITE);
        bubble.setBackground(circle);
        root.addView(bubble, new LinearLayout.LayoutParams(dp(52), dp(52)));

        panel = buildPanel();
        panel.setVisibility(View.GONE);
        // Fixed width that fits the screen; otherwise the panel shrinks to the bubble's width.
        root.addView(panel, new LinearLayout.LayoutParams(
                panelWidth(), ViewGroup.LayoutParams.WRAP_CONTENT));

        params = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = dp(8);
        params.y = dp(120);

        bubble.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY;
            int startX, startY;
            boolean moved;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getRawX();
                        downY = e.getRawY();
                        startX = params.x;
                        startY = params.y;
                        moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
                        if (Math.abs(dx) > dp(6) || Math.abs(dy) > dp(6)) moved = true;
                        params.x = startX + (int) dx;
                        params.y = startY + (int) dy;
                        wm.updateViewLayout(root, params);
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!moved) togglePanel();
                        return true;
                }
                return false;
            }
        });

        wm.addView(root, params);
    }

    private static final String[] QWERTY = {"QWERTYUIOP", "ASDFGHJKL", "ZXCVBNM"};
    private TextView typed;

    /** Panel with two tabs: an on-screen A-Z keyboard and the one-tap cheat list. */
    private View buildPanel() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(4), dp(4), dp(4), dp(4));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#E6121212"));
        bg.setCornerRadius(dp(8));
        box.setBackground(bg);

        final View keyboard = buildKeyboard();
        final View cheats = buildCheatList();
        cheats.setVisibility(View.GONE);

        LinearLayout tabs = new LinearLayout(this);
        Button kbTab = smallButton("⌨ KEYBOARD", "#455A64");
        Button cheatTab = smallButton("★ CHEATS", "#455A64");
        Button close = smallButton("✕", "#B71C1C");
        final android.content.SharedPreferences prefs =
                getSharedPreferences("settings", MODE_PRIVATE);
        KeyInjector.speed = Math.min(prefs.getInt("speed", 2), KeyInjector.SPEEDS.length - 1);
        Button speedBtn = smallButton("", "#6A1B9A");
        speedBtn.setText("⏱ " + KeyInjector.SPEEDS[KeyInjector.speed][0]);
        speedBtn.setOnClickListener(v -> {
            KeyInjector.speed = (KeyInjector.speed + 1) % KeyInjector.SPEEDS.length;
            prefs.edit().putInt("speed", KeyInjector.speed).apply();
            ((Button) v).setText("⏱ " + KeyInjector.SPEEDS[KeyInjector.speed][0]);
        });
        kbTab.setOnClickListener(v -> {
            keyboard.setVisibility(View.VISIBLE);
            cheats.setVisibility(View.GONE);
        });
        cheatTab.setOnClickListener(v -> {
            keyboard.setVisibility(View.GONE);
            cheats.setVisibility(View.VISIBLE);
        });
        close.setOnClickListener(v -> togglePanel());
        tabs.addView(kbTab, weighted(2));
        tabs.addView(cheatTab, weighted(2));
        tabs.addView(speedBtn, weighted(2));
        tabs.addView(close, weighted(1));
        box.addView(tabs, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(36)));

        box.addView(keyboard);
        box.addView(cheats);
        return box;
    }

    private View buildKeyboard() {
        LinearLayout kb = new LinearLayout(this);
        kb.setOrientation(LinearLayout.VERTICAL);

        typed = new TextView(this);
        typed.setTextColor(Color.parseColor("#A5D6A7"));
        typed.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        typed.setTextSize(14);
        typed.setPadding(dp(6), dp(4), dp(6), dp(4));
        typed.setText("Typed: ");
        kb.addView(typed);

        for (String row : QWERTY) {
            LinearLayout line = new LinearLayout(this);
            line.setGravity(Gravity.CENTER);
            line.setWeightSum(10f); // every key is 1/10 of the width, rows stay centered
            for (char c : row.toCharArray()) {
                final String key = String.valueOf(c);
                Button b = smallButton(key, "#37474F");
                b.setTextSize(15);
                b.setOnClickListener(v -> sendLetter(key));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(44), 1f);
                lp.setMargins(dp(2), dp(2), dp(2), dp(2));
                line.addView(b, lp);
            }
            kb.addView(line, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        // Bottom row: backspace, space, enter, clear the "Typed" display.
        LinearLayout bottom = new LinearLayout(this);
        Button back = smallButton("⌫", "#455A64");
        back.setTextSize(16);
        back.setOnClickListener(v -> {
            String shown = typed.getText().toString().substring("Typed: ".length());
            if (!shown.isEmpty()) typed.setText("Typed: " + shown.substring(0, shown.length() - 1));
            sendKeyCode(android.view.KeyEvent.KEYCODE_DEL);
        });
        Button space = smallButton("SPACE", "#37474F");
        space.setOnClickListener(v -> sendKeyCode(android.view.KeyEvent.KEYCODE_SPACE));
        Button enter = smallButton("ENTER ↵", "#2E7D32");
        enter.setOnClickListener(v -> sendKeyCode(android.view.KeyEvent.KEYCODE_ENTER));
        Button clear = smallButton("CLEAR", "#455A64");
        clear.setOnClickListener(v -> typed.setText("Typed: "));
        bottom.addView(back, weighted(1.5f));
        bottom.addView(space, weighted(3f));
        bottom.addView(enter, weighted(2.5f));
        bottom.addView(clear, weighted(1.5f));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
        blp.setMargins(0, dp(2), 0, dp(2));
        kb.addView(bottom, blp);
        return kb;
    }

    private void sendKeyCode(int keyCode) {
        worker.execute(() -> {
            String error = KeyInjector.pressKey(keyCode);
            if (error != null) {
                main.post(() -> Toast.makeText(this, error, Toast.LENGTH_LONG).show());
            }
        });
    }

    private void sendLetter(String key) {
        CharSequence now = typed.getText();
        String shown = now.toString().substring("Typed: ".length()) + key;
        if (shown.length() > 24) shown = shown.substring(shown.length() - 24);
        typed.setText("Typed: " + shown);
        worker.execute(() -> {
            String error = KeyInjector.type(key);
            if (error != null) {
                main.post(() -> Toast.makeText(this, error, Toast.LENGTH_LONG).show());
            }
        });
    }

    private Button smallButton(String text, String color) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(text);
        b.setTextSize(12);
        b.setTextColor(Color.WHITE);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setBackgroundColor(Color.parseColor(color));
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(0, 0, 0, 0);
        return b;
    }

    private LinearLayout.LayoutParams weighted(float w) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, w);
        lp.setMargins(dp(2), 0, dp(2), 0);
        return lp;
    }

    private View buildCheatList() {
        ScrollView scroll = new ScrollView(this);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(200)));

        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(4), dp(4), dp(4), dp(4));
        for (String[] cheat : Cheats.LIST) {
            final String label = cheat[0];
            final String code = cheat[1];
            Button b = new Button(this);
            b.setAllCaps(false);
            b.setText(label + "\n" + code);
            b.setTextSize(11);
            b.setTextColor(Color.WHITE);
            b.setBackgroundColor(Color.parseColor("#2E7D32"));
            b.setMinHeight(0);
            b.setMinimumHeight(0);
            b.setPadding(dp(4), dp(4), dp(4), dp(4));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, dp(2), 0, dp(2));
            b.setOnClickListener(v -> sendCheat(code, label));
            list.addView(b, lp);
        }
        scroll.addView(list);
        return scroll;
    }

    private void togglePanel() {
        boolean show = panel.getVisibility() != View.VISIBLE;
        panel.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) {
            // Keep the whole panel on screen (handles rotation and a bubble near the edge).
            int screenW = getResources().getDisplayMetrics().widthPixels;
            int screenH = getResources().getDisplayMetrics().heightPixels;
            int w = panelWidth();
            panel.getLayoutParams().width = w;
            params.x = Math.max(0, Math.min(params.x, screenW - w));
            params.y = Math.max(0, Math.min(params.y, screenH - dp(340)));
            wm.updateViewLayout(root, params);
        }
    }

    private int panelWidth() {
        int screenW = getResources().getDisplayMetrics().widthPixels;
        return Math.min(dp(440), screenW - dp(8));
    }

    private void sendCheat(String code, String label) {
        panel.setVisibility(View.GONE);
        worker.execute(() -> {
            String error = KeyInjector.type(code);
            main.post(() -> Toast.makeText(this,
                    error == null ? "Cheat sent (" + KeyInjector.lastMethod + "): " + label : error,
                    error == null ? Toast.LENGTH_SHORT : Toast.LENGTH_LONG).show());
        });
    }

    @Override
    public void onDestroy() {
        running = false;
        if (root != null && wm != null) {
            wm.removeView(root);
            root = null;
        }
        worker.execute(HidKeyboard::stop);
        worker.shutdown();
        super.onDestroy();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
