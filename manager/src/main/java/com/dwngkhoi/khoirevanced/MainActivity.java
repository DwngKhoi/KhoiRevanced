package com.dwngkhoi.khoirevanced;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public final class MainActivity extends Activity {
    private TextView output;
    private RootRuntime runtime;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        runtime = new RootRuntime(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        content.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("KhoiRevanced\nStandalone root APK — no LSPosed");
        title.setTextSize(20);
        content.addView(title);

        content.addView(button("Check root / doctor", () -> run("doctor")));
        content.addView(button("Install runtime", () -> run("install")));
        content.addView(button("Runtime status", () -> run("status")));
        content.addView(button("Run script: hello", () -> run("script", "hello")));
        content.addView(button("Run script: device-info", () -> run("script", "device-info")));
        content.addView(button("Show runtime logs", () -> run("logs")));
        content.addView(button("Launch example debug profile", () -> run("launch", "example-debug")));
        content.addView(button("Stop / clean up", () -> run("stop")));

        output = new TextView(this);
        output.setText(
                "Grant root when asked by your root manager, then Install runtime.\n"
                        + "Scripts are allow-listed only (hello, device-info).\n"
                        + "Target context: OnePlus Ace 6T / OxygenOS 16 / arm64-v8a.");
        ScrollView scroll = new ScrollView(this);
        scroll.addView(output);
        content.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(content);
    }

    private Button button(String label, Runnable action) {
        Button button = new Button(this);
        button.setText(label);
        button.setOnClickListener(v -> action.run());
        return button;
    }

    private void run(String action) {
        run(action, null);
    }

    private void run(String action, String arg) {
        output.setText("Running " + action + (arg != null ? (" " + arg) : "") + "…");
        new Thread(() -> {
            RootRuntime.Result result = runtime.run(action, arg);
            runOnUiThread(() -> output.setText(result.text));
        }).start();
    }
}
