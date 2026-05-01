package com.musync.logging

import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Lightweight application-wide logger.
 *
 * Logs are forwarded to [android.util.Log] (so they remain visible in Logcat) and are
 * also persisted to plain-text files in the app's internal storage so they can be
 * exported by the user from the Settings screen.
 *
 * Two files are maintained inside `<filesDir>/logs/`:
 *  - `app.log`    – every entry, all levels.
 *  - `errors.log` – `WARN` and `ERROR` entries only (the file produced by the
 *                   "Export logs" feature).
 *
 * Each file is rotated when it exceeds [MAX_FILE_BYTES]; the previous content is
 * moved to `<name>.1` so we always keep at most ~2 × [MAX_FILE_BYTES] of history.
 *
 * The logger is safe to call from any thread (writes are synchronised) and is a
 * no-op until [init] has been invoked, which happens in [com.musync.MuSyncApplication].
 */
object AppLogger {
    /** Soft cap per log file before rotation. Roughly 256 KB. */
    private const val MAX_FILE_BYTES: Long = 256 * 1024

    private const val LOG_DIR_NAME = "logs"
    private const val APP_LOG_NAME = "app.log"
    private const val ERROR_LOG_NAME = "errors.log"

    private val timestampFormat =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    private val writeLock = Any()

    @Volatile
    private var logsDirectory: File? = null

    /**
     * Initialises the on-disk log files. Must be called from
     * [android.app.Application.onCreate]; subsequent calls are ignored.
     */
    fun init(context: Context) {
        if (logsDirectory != null) return
        synchronized(writeLock) {
            if (logsDirectory != null) return
            val dir = File(context.filesDir, LOG_DIR_NAME)
            if (!dir.exists()) dir.mkdirs()
            logsDirectory = dir
        }
        i(TAG, "AppLogger initialised. Log directory: ${logsDirectory?.absolutePath}")
    }

    fun d(
        tag: String,
        message: String,
    ) {
        Log.d(tag, message)
        write("D", tag, message, throwable = null, alsoWriteErrorLog = false)
    }

    fun i(
        tag: String,
        message: String,
    ) {
        Log.i(tag, message)
        write("I", tag, message, throwable = null, alsoWriteErrorLog = false)
    }

    fun w(
        tag: String,
        message: String,
        throwable: Throwable? = null,
    ) {
        if (throwable != null) Log.w(tag, message, throwable) else Log.w(tag, message)
        write("W", tag, message, throwable, alsoWriteErrorLog = true)
    }

    fun e(
        tag: String,
        message: String,
        throwable: Throwable? = null,
    ) {
        if (throwable != null) Log.e(tag, message, throwable) else Log.e(tag, message)
        write("E", tag, message, throwable, alsoWriteErrorLog = true)
    }

    /** Returns the file containing every entry, or `null` if [init] was never called. */
    fun appLogFile(): File? = logsDirectory?.let { File(it, APP_LOG_NAME) }

    /** Returns the file containing only WARN / ERROR entries (the "error log"). */
    fun errorLogFile(): File? = logsDirectory?.let { File(it, ERROR_LOG_NAME) }

    /** Test-only override of the log directory. */
    internal fun setLogsDirectoryForTesting(directory: File?) {
        synchronized(writeLock) {
            directory?.takeIf { !it.exists() }?.mkdirs()
            logsDirectory = directory
        }
    }

    private fun write(
        level: String,
        tag: String,
        message: String,
        throwable: Throwable?,
        alsoWriteErrorLog: Boolean,
    ) {
        val dir = logsDirectory ?: return
        val line = formatLine(level, tag, message, throwable)
        synchronized(writeLock) {
            appendWithRotation(File(dir, APP_LOG_NAME), line)
            if (alsoWriteErrorLog) {
                appendWithRotation(File(dir, ERROR_LOG_NAME), line)
            }
        }
    }

    private fun formatLine(
        level: String,
        tag: String,
        message: String,
        throwable: Throwable?,
    ): String {
        val timestamp = timestampFormat.format(Date())
        val builder = StringBuilder()
        builder.append(timestamp)
            .append(' ').append(level)
            .append('/').append(tag)
            .append(": ").append(message)
            .append('\n')
        if (throwable != null) {
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            builder.append(sw.toString())
            if (!sw.toString().endsWith("\n")) builder.append('\n')
        }
        return builder.toString()
    }

    private fun appendWithRotation(
        file: File,
        line: String,
    ) {
        try {
            if (file.exists() && file.length() + line.length > MAX_FILE_BYTES) {
                val rotated = File(file.parentFile, file.name + ".1")
                if (rotated.exists()) rotated.delete()
                file.renameTo(rotated)
            }
            file.appendText(line)
        } catch (io: Throwable) {
            // We deliberately swallow IO errors here: logging must never crash the app.
            Log.e(TAG, "Failed to write log entry to ${file.name}", io)
        }
    }

    private const val TAG = "AppLogger"
}
