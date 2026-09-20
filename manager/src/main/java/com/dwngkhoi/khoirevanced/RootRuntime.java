package com.dwngkhoi.khoirevanced;

import android.content.Context;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Extracts the bundled runtime and invokes only allow-listed root actions/scripts via {@code su}.
 */
final class RootRuntime {
    static final List<String> ACTIONS = Collections.unmodifiableList(
            Arrays.asList("doctor", "install", "status", "logs", "stop", "launch", "script"));
    static final List<String> PROFILES = Collections.unmodifiableList(Arrays.asList("example-debug"));
    static final List<String> SCRIPTS = Collections.unmodifiableList(Arrays.asList("hello", "device-info"));

    private static final String[] BUNDLED_ASSETS = {
        "runtime/khoirevanced.sh",
        "runtime/profiles/example-debug.properties",
        "runtime/scripts/hello.sh",
        "runtime/scripts/device-info.sh"
    };

    private final Context context;

    RootRuntime(Context context) {
        this.context = context.getApplicationContext();
    }

    Result run(String action) {
        return run(action, null);
    }

    Result run(String action, String arg) {
        if (!ACTIONS.contains(action)) {
            return new Result("Rejected unsupported action.");
        }
        if ("launch".equals(action) && !PROFILES.contains(arg)) {
            return new Result("Rejected unknown debug profile.");
        }
        if ("script".equals(action) && !SCRIPTS.contains(arg)) {
            return new Result("Rejected unknown script id.");
        }
        if (("launch".equals(action) || "script".equals(action)) && (arg == null || arg.isEmpty())) {
            return new Result("Missing required argument.");
        }
        try {
            File root = new File(context.getFilesDir(), "runtime");
            for (String asset : BUNDLED_ASSETS) {
                boolean executable = asset.endsWith(".sh");
                File dest = new File(root, asset.substring("runtime/".length()));
                extract(asset, dest, executable);
            }
            extractNativeLibrary(root);
            File script = new File(root, "khoirevanced.sh");
            Process chmod = new ProcessBuilder("chmod", "700", script.getAbsolutePath()).start();
            if (chmod.waitFor() != 0) {
                return new Result("Could not mark runtime executable.");
            }
            String command = "KHOIREVANCED_ASSET_ROOT=" + shellQuote(root.getAbsolutePath())
                    + " sh " + shellQuote(script.getAbsolutePath()) + " " + action;
            if (arg != null) {
                command += " " + arg;
            }
            Process process = new ProcessBuilder("su", "-c", command).redirectErrorStream(true).start();
            StringBuilder text = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    text.append(line).append('\n');
                }
            }
            int exit = process.waitFor();
            return new Result("Exit " + exit + "\n" + text);
        } catch (Exception e) {
            return new Result("Runtime error: " + e.getMessage());
        }
    }

    private void extract(String asset, File destination, boolean executable) throws IOException {
        File parent = destination.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Cannot create runtime directory.");
        }
        try (InputStream in = context.getAssets().open(asset);
                OutputStream out = new FileOutputStream(destination)) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) >= 0) {
                out.write(buffer, 0, n);
            }
        }
        destination.setReadable(true, true);
        destination.setWritable(true, true);
        if (executable) {
            destination.setExecutable(true, true);
        }
    }

    private void extractNativeLibrary(File root) throws IOException {
        File source = new File(context.getApplicationInfo().nativeLibraryDir, "libkhoirevanced_agent.so");
        if (!source.isFile()) {
            throw new IOException("Diagnostic agent is unavailable for this ABI.");
        }
        File destination = new File(root, "lib/arm64-v8a/libkhoirevanced_agent.so");
        File parent = destination.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Cannot create agent directory.");
        }
        try (InputStream in = new FileInputStream(source);
                OutputStream out = new FileOutputStream(destination)) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) >= 0) {
                out.write(buffer, 0, n);
            }
        }
        destination.setReadable(true, true);
        destination.setExecutable(true, true);
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    static final class Result {
        final String text;

        Result(String text) {
            this.text = text;
        }
    }
}
