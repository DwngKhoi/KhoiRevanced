package com.dwngkhoi.khoirevanced;

import android.content.Context;
import java.io.*;
import java.nio.charset.StandardCharsets;

final class RootRuntime {
    private final Context context;
    RootRuntime(Context context) { this.context = context.getApplicationContext(); }

    Result run(String action) {
        if (!(action.equals("doctor") || action.equals("install") || action.equals("status") || action.equals("stop"))) return new Result("Rejected unsupported action.");
        try {
            File script = new File(context.getFilesDir(), "runtime/khoirevanced.sh");
            script.getParentFile().mkdirs();
            try (InputStream in = context.getAssets().open("runtime/khoirevanced.sh"); OutputStream out = new FileOutputStream(script)) {
                byte[] buffer = new byte[8192]; int n; while ((n = in.read(buffer)) >= 0) out.write(buffer, 0, n);
            }
            Process chmod = new ProcessBuilder("chmod", "700", script.getAbsolutePath()).start();
            if (chmod.waitFor() != 0) return new Result("Could not mark runtime executable.");
            Process process = new ProcessBuilder("su", "-c", "sh '" + script.getAbsolutePath() + "' " + action).redirectErrorStream(true).start();
            StringBuilder text = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line; while ((line = reader.readLine()) != null) text.append(line).append('\n');
            }
            int exit = process.waitFor();
            return new Result("Exit " + exit + "\n" + text);
        } catch (Exception e) { return new Result("Runtime error: " + e.getMessage()); }
    }
    static final class Result { final String text; Result(String text) { this.text = text; } }
}