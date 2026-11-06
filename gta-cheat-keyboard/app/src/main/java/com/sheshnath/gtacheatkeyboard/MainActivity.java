package com.sheshnath.gtacheatkeyboard;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.provider.Settings;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Setup screen: enable the keyboard, select it, and test it. */
public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        int pad = Math.round(16 * getResources().getDisplayMetrics().density);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("GTA SA Cheat Keyboard");
        title.setTextSize(24);
        title.setTextColor(Color.parseColor("#66BB6A"));
        layout.addView(title);

        TextView steps = new TextView(this);
        steps.setTextSize(15);
        steps.setPadding(0, pad / 2, 0, pad / 2);
        steps.setText(
                "1. Tap \"Enable keyboard\" and turn on GTA Cheat Keyboard.\n"
                        + "2. Tap \"Choose keyboard\" and select it.\n"
                        + "3. Try it in the test box below.\n"
                        + "4. In GTA San Andreas, bring up the keyboard and tap any cheat. "
                        + "Each code is typed as real key presses.\n\n"
                        + "Tip: the 🌐 key switches back to your normal Samsung keyboard.");
        layout.addView(steps);

        Button enable = new Button(this);
        enable.setText("1. Enable keyboard");
        enable.setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)));
        layout.addView(enable);

        Button choose = new Button(this);
        choose.setText("2. Choose keyboard");
        choose.setOnClickListener(v ->
                ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
                        .showInputMethodPicker());
        layout.addView(choose);

        EditText test = new EditText(this);
        test.setHint("3. Test box - tap here and press a cheat");
        layout.addView(test);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(layout);
        setContentView(scroll);
    }
}
