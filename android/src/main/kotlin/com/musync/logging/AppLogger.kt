package com.musync.logging

import android.content.Context
import android.os.Build
import android.os.Environment
import android.util.Log
import com.musync.BuildConfig
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
 * also persisted to plain-text files so they can be pulled off the device directly or
 * exported by the user from the Settings screen.
 *
 * Output goes to the device's shared `Download/MuSync/` folder, which is visible in any
 * file manager. A private copy is always kept under the app's own storage as well,
 * because the shared folder can be unavailable (storage ejected, permission denied on
 * API 26-28) and logging must never depend on it.
 *
 * Two files are maintained:
 *  - `musync.log`        – every entry, all levels.
 *  - `musync-errors.log` – `WARN` and `ERROR` entries only (the file produced by the
 *                          "Export logs" feature).
 *
 * Each file is rotated when it exceeds [MAX_FILE_BYTES]; the previous content is
 * moved to `<name>.1` so we always keep at most ~2 × [MAX_FILE_BYTES] of history.
 *
 * `VERBOSE` entries are suppressed unless [setVerboseEnabled] has enabled them, so
 * the high-volume sync/socket tracing does not consume the log budget during normal
 * use.
 *
 * The logger is safe to call from any thread (writes are synchronised) and is a
 * no-op until [init] has been invoked, which happens in [com.musync.MuSyncApplication].
 */
object AppLogger {
    /** Soft cap per log file before rotation. Roughly 1 MB, sized for verbose tracing. */
    private const val MAX_FILE_BYTES: Long = 1024 * 1024

    private const val LOG_DIR_NAME = "logs"
    private const val APP_LOG_NAME = "musync.log"
    private const val ERROR_LOG_NAME = "musync-errors.log"

    private val timestampFormat =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    private val writeLock = Any()

    @Volatile
    private var logsDirectory: File? = null

    /** Mirror of [APP_LOG_NAME] in the shared `Download/MuSync` folder, when writable. */
    @Volatile
    private var publicSink: PublicLogSink? = null

    /**
     * Where the user can find the logs: the shared `Download/MuSync` path when that
     * folder is writable, otherwise the app-private directory. Shown in Settings.
     */
    @Volatile
    var logLocation: String = ""
        private set

    @Volatile
    private var verboseEnabled: Boolean = BuildConfig.DEBUG

    /** `true` when [v] entries are being recorded. */
    val isVerboseEnabled: Boolean
        get() = verboseEnabled

    /**
     * Enables or disables `VERBOSE` tracing at runtime. Driven by the Developer
     * section of the Settings screen; defaults to on for debug builds.
     */
    fun setVerboseEnabled(enabled: Boolean) {
        if (verboseEnabled == enabled) return
        verboseEnabled = enabled
        i(TAG, "Verbose logging ${if (enabled) "enabled" else "disabled"}")
    }

    /**
     * Initialises the on-disk log files. Must be called from
     * [android.app.Application.onCreate]; subsequent calls are ignored.
     */
    fun init(context: Context) {
        if (logsDirectory != null) return
        val appContext = context.applicationContext
        synchronized(writeLock) {
            if (logsDirectory != null) return
            logsDirectory = resolveLogsDirectory(appContext)
            attachPublicSink(appContext)
        }
        i(TAG, "AppLogger initialised. Logs: $logLocation")
        i(
            TAG,
            "Build ${BuildConfig.VERSION_NAME} (${BuildConfig.BUILD_TYPE}) " +
                "device=${Build.MANUFACTURER} ${Build.MODEL} sdk=${Build.VERSION.SDK_INT} " +
                "serverUrl=${BuildConfig.SERVER_URL} verbose=$verboseEnabled",
        )
    }

    /**
     * Opens the shared `Download/MuSync/musync.log` mirror. Call again after the
     * storage permission is granted on API 26-28, where the folder is unwritable
     * until the user consents.
     */
    fun attachPublicSink(context: Context) {
        val appContext = context.applicationContext
        synchronized(writeLock) {
            if (publicSink != null) return
            val sink = PublicLogSink.open(appContext, APP_LOG_NAME)
            publicSink = sink
            logLocation = sink?.displayPath ?: logsDirectory?.absolutePath.orEmpty()
        }
        if (publicSink == null) {
            Log.w(TAG, "Shared Download folder unavailable; logs stay in app storage")
        }
    }

    /** `true` when logs are being written to the shared Downloads folder. */
    fun isUsingPublicStorage(): Boolean = publicSink != null

    /**
     * Private working directory for the log files. This copy always exists: the
     * shared mirror can be unavailable (no permission, storage ejected, user
     * deleted the file) and logging must not depend on it.
     */
    private fun resolveLogsDirectory(context: Context): File {
        val external =
            try {
                context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            } catch (t: Throwable) {
                Log.w(TAG, "External files directory unavailable", t)
                null
            }
        if (external != null) {
            val dir = File(external, LOG_DIR_NAME)
            if (dir.exists() || dir.mkdirs()) return dir
            Log.w(TAG, "Could not create ${dir.absolutePath}; falling back to internal storage")
        }
        return File(context.filesDir, LOG_DIR_NAME).apply { if (!exists()) mkdirs() }
    }

    /**
     * Records a high-volume tracing entry. The [message] lambda is only invoked when
     * verbose logging is enabled, so callers can interpolate freely without paying
     * for string building when tracing is off.
     */
    fun v(
        tag: String,
        message: () -> String,
    ) {
        if (!verboseEnabled) return
        val text = message()
        Log.v(tag, text)
        write("V", tag, text, throwable = null, alsoWriteErrorLog = false)
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
            // `append` returns the sink to keep using: itself, a replacement after
            // rotation, or null once the shared file is no longer writable.
            publicSink = publicSink?.append(line, MAX_FILE_BYTES)
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
            .append(" [").append(Thread.currentThread().name).append(']')
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
