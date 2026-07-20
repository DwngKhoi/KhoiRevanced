package dev.khoirevanced.manager;

import android.app.Activity;
import android.content.SharedPreferences;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/** First-run root verifier and minimal launcher for the direct-injection runtime. */
public final class MainActivity extends Activity {
    private static final String RUNTIME = "/data/local/tmp/KhoiRevanced.sh";
    private static final String PREFS = "khoirevanced_manager";
    private static final String ROOT_VERIFIED = "root_verified";

    private TextView status;
    private SharedPreferences preferences;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);
        status = findViewById(R.id.status);
        preferences = getSharedPreferences(PREFS, MODE_PRIVATE);

        // Setup UI is deliberately shown only once. Subsequent launches go
        // straight to YouTube after starting the injector.
        if (preferences.getBoolean(ROOT_VERIFIED, false)) {
            launchYouTubeAndClose();
            return;
        }

        ((Button) findViewById(R.id.root_check)).setOnClickListener(v -> checkRoot());
    }

    private void checkRoot() {
        status.setText("Đang kiểm tra quyền root…");
        execute("id", output -> {
            if (output.contains("uid=0")) {
                preferences.edit().putBoolean(ROOT_VERIFIED, true).apply();
                status.setText("Đã cấp quyền root. Lần mở tiếp theo sẽ tự inject và mở YouTube.");
            } else {
                status.setText("Chưa có quyền root\n" + output);
            }
        });
    }

    private void launchYouTubeAndClose() {
        // -mm asks Magisk for its global mount namespace. An APK-root shell
        // otherwise may not see YouTube's /data/user/0 directory.
        Intent youtube = getPackageManager().getLaunchIntentForPackage("com.google.android.youtube");
        if (youtube != null) startActivity(youtube);
        execute("sh " + RUNTIME + " launch youtube", ignored -> { });
        finishAndRemoveTask();
    }

    private void execute(String command, java.util.function.Consumer<String> result) {
        new Thread(() -> {
            StringBuilder output = new StringBuilder();
            try {
                Process process = new ProcessBuilder("su", "-mm", "-c", command)
                        .redirectErrorStream(true)
                        .start();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) output.append(line).append('\n');
                }
                process.waitFor();
            } catch (Exception error) {
                output.append(error);
            }
            String message = output.length() == 0 ? "Hoàn tất" : output.toString().trim();
            runOnUiThread(() -> result.accept(message));
        }, "khoirevanced-root").start();
    }
}
