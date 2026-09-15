package com.dwngkhoi.khoirevanced;

import android.app.Activity;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;

public final class MainActivity extends Activity {
    private TextView output;
    private RootRuntime runtime;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        runtime = new RootRuntime(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        content.setPadding(pad, pad, pad, pad);
        TextView title = new TextView(this);
        title.setText("KhoiRevanced\nStandalone root runtime — no LSPosed");
        title.setTextSize(20);
        content.addView(title);
        content.addView(button("Check root", () -> run("doctor")));
        content.addView(button("Install runtime", () -> run("install")));
        content.addView(button("Runtime status", () -> run("status")));
        content.addView(button("Stop / clean up", () -> run("stop")));
        output = new TextView(this);
        output.setText("Choose Check root to request root access.");
        ScrollView scroll = new ScrollView(this);
        scroll.addView(output);
        content.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(content);
    }

    private Button button(String label, Runnable action) {
        Button button = new Button(this); button.setText(label); button.setOnClickListener(v -> action.run()); return button;
    }
    private void run(String action) {
        output.setText("Running " + action + "…");
        new Thread(() -> {
            RootRuntime.Result result = runtime.run(action);
            runOnUiThread(() -> output.setText(result.text));
        }).start();
    }
}