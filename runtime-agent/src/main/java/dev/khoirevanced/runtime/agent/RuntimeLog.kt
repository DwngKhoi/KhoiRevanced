package dev.khoirevanced.runtime.agent

import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One append-only trace of a whole run, readable by the controller.
 *
 * ## Why this exists
 *
 * The status file answers "did it start", and nothing else. Every failure we
 * chased in this project cost real time because the reason was only ever in
 * either logcat, which is unreliable for an injected process, or in the native
 * log, which only covered the hook engine. The first non-obvious failure, the
 * missing `art::GetMethodShorty` symbol, was invisible from the host: the agent
 * said only "LSPlant ART backend initialization failed".
 *
 * This file is the answer to that. It records every stage of the bootstrap in
 * order, every hook registration, every patch applied or failed, and full stack
 * traces, so a single read of one file explains the run.
 *
 * Logcat still gets everything, because a crash tombstone lives there and a
 * reader may only have one of the two.
 */
object RuntimeLog {

    private const val TAG = "KhoiRevanced"
    private const val FILE_NAME = "runtime.log"
    private const val LIMIT = 512 * 1024

    private val lock = Any()
    private val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private var file: File? = null
    private var dropped = 0

    /** Point the log at the runtime cache directory. */
    fun open(cacheDir: String) {
        synchronized(lock) {
            file = File(cacheDir, FILE_NAME)
        }
    }

    fun info(stage: String, message: String) = write("INFO ", stage, message, null)

    fun warn(stage: String, message: String) = write("WARN ", stage, message, null)

    fun error(stage: String, message: String, throwable: Throwable? = null) =
        write("ERROR", stage, message, throwable)

    /** A named checkpoint in the bootstrap, so ordering is readable at a glance. */
    fun stage(name: String, detail: String = "") {
        info("stage", if (detail.isEmpty()) name else "$name | $detail")
    }

    private fun write(level: String, stage: String, message: String, throwable: Throwable?) {
        val line = buildString {
            append(timestamp.format(Date())).append(' ').append(level)
            append(' ').append(stage).append(": ").append(message)
            if (throwable != null) {
                append('\n')
                append(StringWriter().also { throwable.printStackTrace(PrintWriter(it)) }.toString().trimEnd())
            }
        }
        when (level.trim()) {
            "ERROR" -> Log.e(TAG, "$stage: $message", throwable)
            "WARN" -> Log.w(TAG, "$stage: $message", throwable)
            else -> Log.i(TAG, "$stage: $message")
        }
        synchronized(lock) {
            val target = file ?: return
            try {
                if (target.length() > LIMIT) {
                    // Keep the tail; the interesting part of a run is its end, and
                    // an unbounded log in an app cache dir is its own problem.
                    target.writeText(target.readText().takeLast(LIMIT / 2))
                }
                target.appendText(line + "\n")
            } catch (_: Throwable) {
                // Logging must never be the reason a run fails. Dropped lines are
                // still visible in logcat.
                dropped++
            }
        }
    }

    /** How many lines could not be written, for the status detail. */
    fun droppedLines(): Int = dropped
}
