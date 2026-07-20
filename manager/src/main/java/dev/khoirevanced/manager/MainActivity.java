package dev.khoirevanced.manager;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/** Root manager for the self-extracting direct-injection runtime. */
public final class MainActivity extends Activity {
    private static final String RUNTIME = "/data/local/tmp/KhoiRevanced.sh";
    private TextView status;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);
        status = findViewById(R.id.status);
        ((Button) findViewById(R.id.root_check)).setOnClickListener(v -> checkRoot());
        ((Button) findViewById(R.id.launch_youtube)).setOnClickListener(v -> runRuntime("launch youtube"));
        ((Button) findViewById(R.id.runtime_status)).setOnClickListener(v -> runRuntime("status youtube"));
        checkRoot();
    }

    private void checkRoot() {
        execute("id", output -> status.setText(output.contains("uid=0")
                ? "Root manager đã sẵn sàng"
                : "Chưa có quyền root\n" + output));
    }

    private void runRuntime(String command) {
        status.setText("Đang chạy KhoiRevanced…");
        execute("sh " + RUNTIME + " " + command, output -> status.setText(output));
    }

    private void execute(String command, java.util.function.Consumer<String> result) {
        new Thread(() -> {
            StringBuilder output = new StringBuilder();
            try {
                Process process = new ProcessBuilder("su", "-c", command).redirectErrorStream(true).start();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
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
