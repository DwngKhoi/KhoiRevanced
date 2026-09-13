package com.dwngkhoi.revanced;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.widget.Toast;
import android.widget.Button;
import android.widget.TextView;

import java.io.File;
import java.util.Arrays;

/** Standalone root manager. The self-extracting runtime is an APK asset. */
public final class MainActivity extends Activity {
    private static final String PREFS = "khoirevanced_manager";
    private static final String ROOT_VERIFIED = "root_verified";
    private static final String EXTRA_PROFILE = "profile";

    private static final Profile YOUTUBE =
            new Profile("youtube", "YouTube", "com.google.android.youtube");
    private static final Profile MUSIC =
            new Profile("youtube-music", "YouTube Music", "com.google.android.apps.youtube.music");
    private static final Profile PHOTOS =
            new Profile("google-photos", "Google Photos", "com.google.android.apps.photos");

    private SharedPreferences preferences;
    private TextView status;
    private Profile activeProfile;
    private String lastLog = "";

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        openRequestedProfileOrDashboard();
    }

    @Override
    protected void onNewIntent(Intent intent) {
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
        status.setText("REQUESTING ROOT ACCESS...");
        RootShell.run("id", result -> runOnUiThread(() -> {
            if (result.isSuccess() && result.output.contains("uid=0")) {
                preferences.edit().putBoolean(ROOT_VERIFIED, true).apply();
                showDashboard();
            } else {
                status.setText("ROOT ACCESS DENIED\n" + result.output);
            }
        }));
    }

    private void showDashboard() {
        setContentView(R.layout.activity_dashboard);
        removeLegacyDynamicShortcuts();
        ((Button) findViewById(R.id.launch_youtube))
                .setOnClickListener(v -> showLoadingAndLaunch(YOUTUBE));
        ((Button) findViewById(R.id.launch_music))
                .setOnClickListener(v -> showLoadingAndLaunch(MUSIC));
        ((Button) findViewById(R.id.launch_photos))
                .setOnClickListener(v -> showLoadingAndLaunch(PHOTOS));
        ((Button) findViewById(R.id.shortcut_youtube))
                .setOnClickListener(v -> requestHomeShortcut(YOUTUBE));
        ((Button) findViewById(R.id.shortcut_music))
                .setOnClickListener(v -> requestHomeShortcut(MUSIC));
        ((Button) findViewById(R.id.shortcut_photos))
                .setOnClickListener(v -> requestHomeShortcut(PHOTOS));
    }

    private void removeLegacyDynamicShortcuts() {
        ShortcutManager shortcuts = getSystemService(ShortcutManager.class);
        if (shortcuts != null) {
            shortcuts.removeDynamicShortcuts(Arrays.asList(
                    "launch-youtube", "launch-youtube-music", "launch-google-photos"));
        }
    }

    private void requestHomeShortcut(Profile profile) {
        ShortcutManager shortcuts = getSystemService(ShortcutManager.class);
        if (shortcuts == null || !shortcuts.isRequestPinShortcutSupported()) return;
        String shortcutId = "launch-" + profile.id + "-" + System.currentTimeMillis();
        ShortcutInfo shortcut = new ShortcutInfo.Builder(this, shortcutId)
                .setShortLabel(profile.label)
                .setLongLabel("KhoiRevanced • " + profile.label)
                .setIcon(officialAppIcon(profile.packageName))
                .setIntent(new Intent(this, ShortcutActivity.class)
                        .setAction("com.dwngkhoi.revanced.SHORTCUT." + profile.id)
                        .putExtra(EXTRA_PROFILE, profile.id))
                .build();
        shortcuts.requestPinShortcut(shortcut, null);
    }

    private Icon officialAppIcon(String packageName) {
        try {
            int iconResource = getPackageManager().getApplicationInfo(packageName, 0).icon;
            return Icon.createWithResource(packageName, iconResource);
        } catch (Exception ignored) {
            return Icon.createWithResource(this, R.drawable.khoirevanced_icon);
        }
    }

    private void showLoadingAndLaunch(Profile profile) {
        activeProfile = profile;
        lastLog = "";
        setContentView(R.layout.activity_loading);
        status = findViewById(R.id.loading_status);
        Button copyLog = findViewById(R.id.copy_log);
        Button retry = findViewById(R.id.retry_injection);
        Button dashboard = findViewById(R.id.back_dashboard);
        copyLog.setEnabled(false);
        copyLog.setOnClickListener(v -> copyLog());
        retry.setOnClickListener(v -> showLoadingAndLaunch(activeProfile));
        dashboard.setOnClickListener(v -> showDashboard());
        status.setText("PREPARING " + profile.label.toUpperCase() + "...");
        new Thread(() -> {
            try {
                File runtime = RuntimeAsset.extract(this);
                runOnUiThread(() ->
                        status.setText("INJECTING " + profile.label.toUpperCase() + "..."));
                launchProfile(runtime, profile);
            } catch (Exception error) {
                showRuntimeError(error.getMessage() == null ? error.toString() : error.getMessage());
            }
        }, "khoirevanced-launch").start();
    }

    private void launchProfile(File runtime, Profile profile) {
        String quoted = "'" + runtime.getAbsolutePath().replace("'", "'\\\"'\\\"'") + "'";
        RootShell.run("sh " + quoted + " launch " + profile.id,
                result -> runOnUiThread(() -> {
                    if (result.isSuccess()
                            && result.output.contains("agent loaded")
                            && result.output.contains("state=ready")
                            && result.output.contains("module=nexalloy-loaded")) {
                        finishAndRemoveTask();
                    } else {
                        showRuntimeError(result.output);
                    }
                }));
    }

    private void showRuntimeError(String message) {
        runOnUiThread(() -> {
            lastLog = message == null || message.isEmpty() ? "Unknown runtime error" : message;
            status.setText(lastLog);
            Button copyLog = findViewById(R.id.copy_log);
            if (copyLog != null) copyLog.setEnabled(true);
            findViewById(R.id.loading_progress).setVisibility(android.view.View.GONE);
        });
    }

    private void copyLog() {
        if (lastLog.isEmpty()) return;
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("KhoiRevanced runtime log", lastLog));
            Toast.makeText(this, "Log đã được copy", Toast.LENGTH_SHORT).show();
        }
    }

    private static Profile profileFor(String id) {
        if (YOUTUBE.id.equals(id)) return YOUTUBE;
        if (MUSIC.id.equals(id)) return MUSIC;
        if (PHOTOS.id.equals(id)) return PHOTOS;
        return null;
    }

    private static final class Profile {
        final String id;
        final String label;
        final String packageName;

        Profile(String id, String label, String packageName) {
            this.id = id;
            this.label = label;
            this.packageName = packageName;
        }
    }
}
