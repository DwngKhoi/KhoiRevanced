package com.dwngkhoi.revanced;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

/** Root manager. The self-extracting runtime is embedded in this APK's assets. */
public final class MainActivity extends Activity {
    private static final String PREFS = "khoirevanced_manager";
    private static final String ROOT_VERIFIED = "root_verified";
    private static final String EXTRA_PROFILE = "profile";
    private static final String RUNTIME_ASSET = "KhoiRevanced.sh";

    private static final Profile YOUTUBE = new Profile("youtube", "YouTube", "com.google.android.youtube");
    private static final Profile MUSIC = new Profile("youtube-music", "YouTube Music", "com.google.android.apps.youtube.music");
    private static final Profile PHOTOS = new Profile("google-photos", "Google Photos", "com.google.android.apps.photos");

    private SharedPreferences preferences;
    private TextView status;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        openRequestedProfileOrDashboard();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        openRequestedProfileOrDashboard();
    }

    private void openRequestedProfileOrDashboard() {
        Profile requested = profileFor(getIntent().getStringExtra(EXTRA_PROFILE));
        if (!preferences.getBoolean(ROOT_VERIFIED, false)) {
            showFirstRun();
        } else if (requested != null) {
            showLoadingAndLaunch(requested);
        } else {
            showDashboard();
        }
    }

    private void showFirstRun() {
        setContentView(R.layout.activity_main);
        status = findViewById(R.id.status);
        ((Button) findViewById(R.id.root_check)).setOnClickListener(v -> verifyRoot());
    }

    private void verifyRoot() {
        status.setText("REQUESTING ROOT ACCESS…");
        executeRoot("id", output -> {
            if (output.contains("uid=0")) {
                preferences.edit().putBoolean(ROOT_VERIFIED, true).apply();
                showDashboard();
            } else status.setText("ROOT ACCESS DENIED\n" + output);
        });
    }

    private void showDashboard() {
        setContentView(R.layout.activity_dashboard);
        ((Button) findViewById(R.id.launch_youtube)).setOnClickListener(v -> showLoadingAndLaunch(YOUTUBE));
        ((Button) findViewById(R.id.launch_music)).setOnClickListener(v -> showLoadingAndLaunch(MUSIC));
        ((Button) findViewById(R.id.launch_photos)).setOnClickListener(v -> showLoadingAndLaunch(PHOTOS));
    }

    private void showLoadingAndLaunch(Profile profile) {
        setContentView(R.layout.activity_loading);
        status = findViewById(R.id.loading_status);
        status.setText("PREPARING " + profile.label.toUpperCase() + "…");
        new Thread(() -> {
            try {
                File runtime = extractRuntime();
                runOnUiThread(() -> status.setText("INJECTING " + profile.label.toUpperCase() + "…"));
                launchProfile(runtime, profile);
            } catch (Exception error) {
                showRuntimeError(error.getMessage());
            }
        }, "khoirevanced-launch").start();
    }

    private File extractRuntime() throws IOException {
        File runtimeDir = new File(getCacheDir(), "runtime");
        if (!runtimeDir.exists() && !runtimeDir.mkdirs()) throw new IOException("Cannot create runtime cache");
        File destination = new File(runtimeDir, RUNTIME_ASSET);
        try (InputStream input = getAssets().open(RUNTIME_ASSET);
             FileOutputStream output = new FileOutputStream(destination, false)) {
            byte[] buffer = new byte[64 * 1024];
            for (int count; (count = input.read(buffer)) != -1; ) output.write(buffer, 0, count);
        }
        destination.setReadable(true, false);
        return destination;
    }

    private void launchProfile(File runtime, Profile profile) {
        String quoted = "'" + runtime.getAbsolutePath().replace("'", "'\\\"'\\\"'") + "'";
        executeRoot("sh " + quoted + " launch " + profile.id, output -> {
            if (output.contains("agent loaded")) finishAndRemoveTask();
            else showRuntimeError(output);
        });
    }

    private void showRuntimeError(String message) {
        runOnUiThread(() -> {
            status.setText("INJECTION FAILED\n" + message);
            findViewById(R.id.loading_progress).setVisibility(android.view.View.GONE);
        });
    }

    private void executeRoot(String command, java.util.function.Consumer<String> result) {
        new Thread(() -> {
            StringBuilder output = new StringBuilder();
            try {
                Process process = new ProcessBuilder("su", "-mm", "-c", command).redirectErrorStream(true).start();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line; while ((line = reader.readLine()) != null) output.append(line).append('\n');
                }
                process.waitFor();
            } catch (Exception error) { output.append(error); }
            String message = output.length() == 0 ? "COMPLETE" : output.toString().trim();
            runOnUiThread(() -> result.accept(message));
        }, "khoirevanced-root").start();
    }

    private static Profile profileFor(String id) {
        if (YOUTUBE.id.equals(id)) return YOUTUBE;
        if (MUSIC.id.equals(id)) return MUSIC;
        if (PHOTOS.id.equals(id)) return PHOTOS;
        return null;
    }

    private static final class Profile {
        final String id, label, packageName;
        Profile(String id, String label, String packageName) {
            this.id = id; this.label = label; this.packageName = packageName;
        }
    }
}
