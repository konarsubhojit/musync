package com.musync.logging

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.File
import java.io.OutputStream

/**
 * Mirrors log output to the device's shared `Download/MuSync` folder so the files
 * can be opened with any file manager or pulled over USB without ADB.
 *
 * Two very different storage APIs are needed because the shared Downloads folder
 * is not directly writable on modern Android:
 *  - **API 29+ (scoped storage)**: entries are created through [MediaStore.Downloads]
 *    with `RELATIVE_PATH` set to `Download/MuSync`. No permission is required.
 *  - **API 26-28**: the folder is a normal path, but writing requires the
 *    `WRITE_EXTERNAL_STORAGE` runtime permission.
 *
 * The sink is best-effort: if the shared folder is unavailable (permission denied,
 * storage ejected, MediaStore rejects the insert) it silently disables itself and
 * [AppLogger] keeps writing to its private copy. Logging must never break the app.
 *
 * A single [OutputStream] is held open in append mode and flushed after each line,
 * because re-opening a `content://` URI per log entry is far too expensive.
 */
internal class PublicLogSink private constructor(
    private val stream: OutputStream,
    /** Human-readable location shown in Settings. */
    val displayPath: String,
    private val sizeOf: () -> Long,
    private val reopen: () -> PublicLogSink?,
) {
    private var written: Long = sizeOf()

    /**
     * Appends [line], rotating first when the file would exceed [maxBytes].
     *
     * @return this sink, or a replacement after rotation, or `null` if the sink
     *   died and should be dropped.
     */
    fun append(
        line: String,
        maxBytes: Long,
    ): PublicLogSink? {
        val bytes = line.toByteArray()
        return try {
            if (written + bytes.size > maxBytes) {
                // The private copy keeps rotated history, so the shared mirror is
                // simply truncated rather than accumulating numbered backups.
                close()
                val replacement = reopen() ?: return null
                replacement.append(line, maxBytes)
            } else {
                stream.write(bytes)
                stream.flush()
                written += bytes.size
                this
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Shared log sink failed; disabling it", t)
            close()
            null
        }
    }

    fun close() {
        try {
            stream.close()
        } catch (_: Throwable) {
            // Nothing useful to do; the sink is being discarded either way.
        }
    }

    companion object {
        private const val TAG = "PublicLogSink"
        private const val FOLDER_NAME = "MuSync"
        private const val MIME_TEXT = "text/plain"

        /**
         * Opens (or creates) `Download/MuSync/[fileName]` for appending.
         *
         * @param truncate `true` to start from an empty file, used when rotating.
         * @return the sink, or `null` when the shared folder cannot be written to.
         */
        fun open(
            context: Context,
            fileName: String,
            truncate: Boolean = false,
        ): PublicLogSink? =
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    openViaMediaStore(context, fileName, truncate)
                } else {
                    openLegacyFile(context, fileName, truncate)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Could not open shared log file $fileName", t)
                null
            }

        /** `true` when the shared Downloads folder is writable on this device. */
        fun isAvailable(context: Context): Boolean =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                true
            } else {
                hasLegacyPermission(context) && isExternalStorageWritable()
            }

        private fun hasLegacyPermission(context: Context): Boolean =
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
            ) == PackageManager.PERMISSION_GRANTED

        private fun isExternalStorageWritable(): Boolean =
            Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED

        // ── API 29+ ───────────────────────────────────────────────────────────
        private fun openViaMediaStore(
            context: Context,
            fileName: String,
            truncate: Boolean,
        ): PublicLogSink? {
            val resolver = context.contentResolver
            val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER_NAME"
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI

            val existing = findExisting(context, fileName, relativePath)
            if (existing != null && truncate) {
                resolver.delete(existing, null, null)
            }

            val uri =
                existing.takeIf { !truncate }
                    ?: resolver.insert(
                        collection,
                        ContentValues().apply {
                            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                            put(MediaStore.Downloads.MIME_TYPE, MIME_TEXT)
                            put(MediaStore.Downloads.RELATIVE_PATH, relativePath)
                        },
                    )
                    ?: return null

            // "wa" appends, so restarting the app extends the existing log instead
            // of silently discarding the previous session.
            val mode = if (truncate) "wt" else "wa"
            val stream = resolver.openOutputStream(uri, mode) ?: return null

            return PublicLogSink(
                stream = stream,
                displayPath = "$relativePath/$fileName",
                sizeOf = { sizeOf(context, uri) },
                reopen = { open(context, fileName, truncate = true) },
            )
        }

        private fun findExisting(
            context: Context,
            fileName: String,
            relativePath: String,
        ): Uri? {
            val projection = arrayOf(MediaStore.Downloads._ID)
            val selection =
                "${MediaStore.Downloads.DISPLAY_NAME} = ? AND ${MediaStore.Downloads.RELATIVE_PATH} = ?"
            // MediaStore stores RELATIVE_PATH with a trailing separator.
            val args = arrayOf(fileName, "$relativePath/")
            context.contentResolver
                .query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, projection, selection, args, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID))
                        return MediaStore.Downloads.EXTERNAL_CONTENT_URI.buildUpon()
                            .appendPath(id.toString())
                            .build()
                    }
                }
            return null
        }

        private fun sizeOf(
            context: Context,
            uri: Uri,
        ): Long =
            try {
                context.contentResolver
                    .openFileDescriptor(uri, "r")
                    ?.use { it.statSize.coerceAtLeast(0L) } ?: 0L
            } catch (_: Throwable) {
                0L
            }

        // ── API 26-28 ─────────────────────────────────────────────────────────
        private fun openLegacyFile(
            context: Context,
            fileName: String,
            truncate: Boolean,
        ): PublicLogSink? {
            if (!hasLegacyPermission(context) || !isExternalStorageWritable()) return null

            @Suppress("DEPRECATION")
            val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val dir = File(downloads, FOLDER_NAME)
            if (!dir.exists() && !dir.mkdirs()) return null

            val file = File(dir, fileName)
            if (truncate && file.exists()) file.delete()

            // `append` is the second FileOutputStream parameter: keep existing content
            // unless the caller asked for a fresh file.
            val stream = java.io.FileOutputStream(file, !truncate)
            return PublicLogSink(
                stream = stream,
                displayPath = file.absolutePath,
                sizeOf = { if (file.exists()) file.length() else 0L },
                reopen = { open(context, fileName, truncate = true) },
            )
        }
    }
}
