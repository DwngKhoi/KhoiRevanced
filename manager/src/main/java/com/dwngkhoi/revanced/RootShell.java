package com.dwngkhoi.revanced;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

/** Small boundary for the one privileged operation performed by Manager. */
final class RootShell {
    interface Callback {
        void complete(Result result);
    }

    static final class Result {
        final int exitCode;
        final String output;

        Result(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }

        boolean isSuccess() {
            return exitCode == 0;
        }
    }

    private RootShell() {}

    static void run(String command, Callback callback) {
        new Thread(() -> callback.complete(runBlocking(command)), "khoirevanced-root").start();
    }

    private static Result runBlocking(String command) {
        StringBuilder output = new StringBuilder();
        int exitCode = 127;
        try {
            Process process = new ProcessBuilder("su", "-mm", "-c", command)
                    .redirectErrorStream(true)
                    .start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append('\n');
                }
            }
            exitCode = process.waitFor();
        } catch (IOException error) {
            output.append(error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            output.append(error);
        }
        String text = output.length() == 0 ? "COMPLETE" : output.toString().trim();
        return new Result(exitCode, text);
    }
}
