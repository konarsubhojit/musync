package com.musync.logging

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Copies the app's persisted log files into a folder selected by the user via the
 * Storage Access Framework.
 *
 * The picked location is represented by a tree [Uri] obtained from
 * [androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree].
 * Files are written using [DocumentFile] / the platform [android.content.ContentResolver]
 * so the export works on any storage provider the user chooses (internal storage,
 * SD card, Drive, etc.).
 */
object LogExporter {
    private const val MIME_TEXT = "text/plain"

    private val fileNameTimestampFormat =
        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)

    /** Result of an export attempt. */
    sealed class Result {
        /** All requested log files were copied to the picked folder. */
        data class Success(val fileNames: List<String>) : Result()

        /** No log file existed yet (nothing to export). */
        data object Empty : Result()

        /** Something went wrong; [message] is suitable for showing to the user. */
        data class Failure(val message: String) : Result()
    }

    /**
     * Exports the error log (and the full app log, if present) to the directory
     * pointed to by [treeUri].
     */
    fun exportLogs(
        context: Context,
        treeUri: Uri,
    ): Result {
        val errorLog = AppLogger.errorLogFile()
        val appLog = AppLogger.appLogFile()

        val sources =
            buildList {
                if (errorLog != null && errorLog.exists() && errorLog.length() > 0) {
                    add(errorLog)
                }
                if (appLog != null && appLog.exists() && appLog.length() > 0) {
                    add(appLog)
                }
            }
        if (sources.isEmpty()) return Result.Empty

        val targetDir =
            DocumentFile.fromTreeUri(context, treeUri)
                ?: return Result.Failure("Unable to open the selected folder.")
        if (!targetDir.isDirectory || !targetDir.canWrite()) {
            return Result.Failure("The selected folder is not writable.")
        }

        val timestamp = fileNameTimestampFormat.format(Date())
        val written = mutableListOf<String>()
        return try {
            for (source in sources) {
                val targetName = "musync-${source.nameWithoutExtension}-$timestamp.log"
                val destFile =
                    targetDir.createFile(MIME_TEXT, targetName)
                        ?: return Result.Failure("Could not create $targetName in the selected folder.")
                copyTo(context, source, destFile.uri)
                written.add(destFile.name ?: targetName)
            }
            AppLogger.i(TAG, "Exported ${written.size} log file(s) to $treeUri")
            Result.Success(written)
        } catch (t: Throwable) {
            AppLogger.e(TAG, "Failed to export logs to $treeUri", t)
            Result.Failure(t.localizedMessage ?: "Unknown error while exporting logs.")
        }
    }

    private fun copyTo(
        context: Context,
        source: File,
        destinationUri: Uri,
    ) {
        context.contentResolver.openOutputStream(destinationUri, "w").use { out ->
            requireNotNull(out) { "ContentResolver returned no output stream for $destinationUri" }
            source.inputStream().use { input -> input.copyTo(out) }
            out.flush()
        }
    }

    private const val TAG = "LogExporter"
}
