package com.dwngkhoi.khoirevanced;

import android.content.Context;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

final class RootRuntime {
    private static final List<String> ACTIONS = Arrays.asList("doctor", "install", "status", "logs", "stop", "launch");
    private static final List<String> PROFILES = Arrays.asList("example-debug");
    private final Context context;
    RootRuntime(Context context) { this.context = context.getApplicationContext(); }

    Result run(String action) { return run(action, null); }
    Result run(String action, String profile) {
        if (!ACTIONS.contains(action)) return new Result("Rejected unsupported action.");
        if ("launch".equals(action) && !PROFILES.contains(profile)) return new Result("Rejected unknown debug profile.");
        try {
            File root = new File(context.getFilesDir(), "runtime");
            extract("runtime/khoirevanced.sh", new File(root, "khoirevanced.sh"), true);
            extract("runtime/profiles/example-debug.properties", new File(root, "profiles/example-debug.properties"), false);
            extractNativeLibrary(root);
            File script = new File(root, "khoirevanced.sh");
            Process chmod = new ProcessBuilder("chmod", "700", script.getAbsolutePath()).start();
            if (chmod.waitFor() != 0) return new Result("Could not mark runtime executable.");
            String command = "KHOIREVANCED_ASSET_ROOT=" + shellQuote(root.getAbsolutePath()) + " sh " + shellQuote(script.getAbsolutePath()) + " " + action;
            if (profile != null) command += " " + profile;
            Process process = new ProcessBuilder("su", "-c", command).redirectErrorStream(true).start();
            StringBuilder text = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line; while ((line = reader.readLine()) != null) text.append(line).append('\n');
            }
            int exit = process.waitFor();
            return new Result("Exit " + exit + "\n" + text);
        } catch (Exception e) { return new Result("Runtime error: " + e.getMessage()); }
    }
    private void extract(String asset, File destination, boolean executable) throws IOException {
        File parent = destination.getParentFile(); if (!parent.exists() && !parent.mkdirs()) throw new IOException("Cannot create runtime directory.");
        try (InputStream in = context.getAssets().open(asset); OutputStream out = new FileOutputStream(destination)) {
            byte[] buffer = new byte[8192]; int n; while ((n = in.read(buffer)) >= 0) out.write(buffer, 0, n);
        }
        destination.setReadable(true, true); destination.setWritable(true, true); if (executable) destination.setExecutable(true, true);
    }
    private void extractNativeLibrary(File root) throws IOException {
        File source = new File(context.getApplicationInfo().nativeLibraryDir, "libkhoirevanced_agent.so");
        if (!source.isFile()) throw new IOException("Diagnostic agent is unavailable for this ABI.");
        File destination = new File(root, "lib/arm64-v8a/libkhoirevanced_agent.so");
        File parent = destination.getParentFile(); if (!parent.exists() && !parent.mkdirs()) throw new IOException("Cannot create agent directory.");
        try (InputStream in = new FileInputStream(source); OutputStream out = new FileOutputStream(destination)) {
            byte[] buffer = new byte[8192]; int n; while ((n = in.read(buffer)) >= 0) out.write(buffer, 0, n);
        }
        destination.setReadable(true, true); destination.setExecutable(true, true);
    }
    private static String shellQuote(String value) { return "'" + value.replace("'", "'\\''") + "'"; }
    static final class Result { final String text; Result(String text) { this.text = text; } }
}