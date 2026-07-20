package com.dwngkhoi.revanced;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/** Invisible shortcut trampoline; never leaves a duplicate Manager task behind. */
public final class ShortcutActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        Intent launch = new Intent(this, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("profile", getIntent().getStringExtra("profile"));
        startActivity(launch);
        finish();
    }
}
