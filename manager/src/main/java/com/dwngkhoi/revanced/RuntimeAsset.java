package com.dwngkhoi.revanced;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** Copies the immutable root runtime from the APK into the app cache. */
final class RuntimeAsset {
    private static final String FILE_NAME = "KhoiRevanced.sh";

    private RuntimeAsset() {}

    static File extract(Context context) throws IOException {
        File directory = new File(context.getCacheDir(), "runtime");
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException("Cannot create runtime cache");
        }
        File destination = new File(directory, FILE_NAME);
        try (InputStream input = context.getAssets().open(FILE_NAME);
             FileOutputStream output = new FileOutputStream(destination, false)) {
            byte[] buffer = new byte[64 * 1024];
            for (int count; (count = input.read(buffer)) != -1; ) {
                output.write(buffer, 0, count);
            }
        }
        if (!destination.setReadable(true, false)) {
            throw new IOException("Runtime asset is not readable");
        }
        return destination;
    }
}
