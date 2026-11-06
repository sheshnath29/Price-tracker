package com.sheshnath.gtacheatkeyboard;

import android.graphics.Color;
import android.graphics.Typeface;
import android.inputmethodservice.InputMethodService;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputConnection;
import android.widget.Button;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Soft keyboard with one-tap GTA San Andreas cheat buttons.
 *
 * Cheats are sent as real key events (A-Z key down/up), one letter at a time,
 * because the game reads raw key presses rather than committed text.
 */
public class CheatKeyboardService extends InputMethodService {

    private static final long KEY_DELAY_MS = 45;
    private static final String[] QWERTY = {"QWERTYUIOP", "ASDFGHJKL", "ZXCVBNM"};

    private final Handler handler = new Handler(Looper.getMainLooper());
    private LinearLayout root;
    private View cheatPanel;
    private View letterPanel;
    private TextView status;
    private boolean typing;

    @Override
    public View onCreateInputView() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#121212"));
        root.setPadding(dp(4), dp(4), dp(4), dp(4));

        status = new TextView(this);
        status.setTextColor(Color.parseColor("#A5D6A7"));
        status.setTextSize(13);
        status.setPadding(dp(6), dp(2), dp(6), dp(4));
        status.setText("Tap a cheat to type it into the game");
        root.addView(status);

        cheatPanel = buildCheatPanel();
        letterPanel = buildLetterPanel();
        letterPanel.setVisibility(View.GONE);
        root.addView(cheatPanel);
        root.addView(letterPanel);
        root.addView(buildBottomRow());
        return root;
    }

    private View buildCheatPanel() {
        ScrollView scroll = new ScrollView(this);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(230)));
        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(2);
        for (String[] cheat : Cheats.LIST) {
            final String label = cheat[0];
            final String code = cheat[1];
            Button b = makeButton(label + "\n" + code, "#2E7D32");
            b.setAllCaps(false);
            b.setTextSize(12);
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams(
                    GridLayout.spec(GridLayout.UNDEFINED),
                    GridLayout.spec(GridLayout.UNDEFINED, 1f));
            lp.width = 0;
            lp.height = dp(56);
            lp.setMargins(dp(2), dp(2), dp(2), dp(2));
            b.setLayoutParams(lp);
            b.setOnClickListener(v -> typeCode(code, label));
            grid.addView(b);
        }
        scroll.addView(grid);
        return scroll;
    }

    private View buildLetterPanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(230)));
        for (String row : QWERTY) {
            LinearLayout line = new LinearLayout(this);
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setGravity(Gravity.CENTER);
            line.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            for (char c : row.toCharArray()) {
                final char ch = c;
                Button b = makeButton(String.valueOf(c), "#37474F");
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
                lp.setMargins(dp(2), dp(2), dp(2), dp(2));
                b.setLayoutParams(lp);
                b.setOnClickListener(v -> sendLetter(ch));
                line.addView(b);
            }
            panel.addView(line);
        }
        return panel;
    }

    private View buildBottomRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));

        Button toggle = makeButton("ABC", "#455A64");
        toggle.setOnClickListener(v -> {
            boolean showLetters = letterPanel.getVisibility() != View.VISIBLE;
            letterPanel.setVisibility(showLetters ? View.VISIBLE : View.GONE);
            cheatPanel.setVisibility(showLetters ? View.GONE : View.VISIBLE);
            ((Button) v).setText(showLetters ? "CHEATS" : "ABC");
        });
        Button switchIme = makeButton("🌐", "#455A64");
        switchIme.setOnClickListener(v -> {
            if (!switchToNextInputMethod(false)) {
                android.view.inputmethod.InputMethodManager imm =
                        (android.view.inputmethod.InputMethodManager)
                                getSystemService(INPUT_METHOD_SERVICE);
                imm.showInputMethodPicker();
            }
        });
        Button back = makeButton("⌫", "#455A64");
        back.setOnClickListener(v -> sendKey(KeyEvent.KEYCODE_DEL));
        Button enter = makeButton("ENTER", "#455A64");
        enter.setOnClickListener(v -> sendKey(KeyEvent.KEYCODE_ENTER));
        Button hide = makeButton("HIDE", "#455A64");
        hide.setOnClickListener(v -> requestHideSelf(0));

        for (Button b : new Button[]{toggle, switchIme, back, enter, hide}) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
            lp.setMargins(dp(2), dp(2), dp(2), dp(2));
            b.setLayoutParams(lp);
            row.addView(b);
        }
        return row;
    }

    private Button makeButton(String text, String color) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextColor(Color.WHITE);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setBackgroundColor(Color.parseColor(color));
        b.setPadding(dp(2), 0, dp(2), 0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        return b;
    }

    /** Types a cheat code letter by letter with a short delay between keys. */
    private void typeCode(String code, String label) {
        if (typing) return;
        if (getCurrentInputConnection() == null) {
            Toast.makeText(this, "No input target - open the game first", Toast.LENGTH_SHORT).show();
            return;
        }
        typing = true;
        status.setText("Typing " + code + "…");
        for (int i = 0; i < code.length(); i++) {
            final char c = code.charAt(i);
            handler.postDelayed(() -> sendLetter(c), i * KEY_DELAY_MS);
        }
        handler.postDelayed(() -> {
            typing = false;
            status.setText("Sent: " + label + " (" + code + ")");
        }, code.length() * KEY_DELAY_MS);
    }

    private void sendLetter(char c) {
        char upper = Character.toUpperCase(c);
        if (upper < 'A' || upper > 'Z') return;
        sendKey(KeyEvent.KEYCODE_A + (upper - 'A'));
    }

    private void sendKey(int keyCode) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        long now = SystemClock.uptimeMillis();
        ic.sendKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0));
        ic.sendKeyEvent(new KeyEvent(now, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, keyCode, 0));
    }

    @Override
    public boolean onEvaluateFullscreenMode() {
        // Never take over the whole screen in landscape; keep the game visible.
        return false;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
