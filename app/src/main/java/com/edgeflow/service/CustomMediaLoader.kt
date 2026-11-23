package com.edgeflow.service

import android.content.Context
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Zero-dependency native Android 9+ (`ImageDecoder` / `AnimatedImageDrawable`) loader and
 * cross-process internal storage manager for custom transparent GIF / Animated WebP / PNG / JPEG
 * side-edge feedback media.
 *
 * Guarantees:
 * - Zero external image libraries (no Glide/Coil) and zero Jetpack Compose imports.
 * - Mandatory downsampling via `ImageDecoder.OnHeaderDecodedListener` to max `256px` long edge
 *   to prevent memory spikes and OOMs even on huge GIFs/photos.
 * - Copies picked `content://` URIs into internal `filesDir/custom_media/edge_indicator.media`
 *   with a hard 15MB safety limit so both `:settings` and the resident process read reliably
 *   across process boundaries and device reboots without URI permission expiration.
 */
object CustomMediaLoader {

    const val CUSTOM_MEDIA_DIR = "custom_media"
    const val CUSTOM_MEDIA_FILE_NAME = "edge_indicator.media"
    const val MAX_FILE_SIZE_BYTES = 15L * 1024L * 1024L
    const val MAX_DECODE_LONG_EDGE_PX = 256

    private const val COPY_BUFFER_SIZE = 16 * 1024

    sealed class CopyResult {
        data class Success(val filePath: String, val version: Long) : CopyResult()
        data object TooLarge : CopyResult()
        data object InvalidFormat : CopyResult()
        data object IoError : CopyResult()
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val decodeExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "edgeflow-media-decode").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY - 1
        }
    }

    /**
     * Copies a user-selected `content://` media stream into app-private internal storage,
     * validates decodability with [ImageDecoder], and atomically commits the target file.
     * Must be invoked on a background thread.
     */
    fun copyPickedMediaToInternal(context: Context, sourceUri: Uri): CopyResult {
        val dir = File(context.applicationContext.filesDir, CUSTOM_MEDIA_DIR)
        if (!dir.exists() && !dir.mkdirs()) {
            return CopyResult.IoError
        }

        val tempFile = File(dir, "$CUSTOM_MEDIA_FILE_NAME.tmp")
        val targetFile = File(dir, CUSTOM_MEDIA_FILE_NAME)

        try {
            val input = context.contentResolver.openInputStream(sourceUri) ?: return CopyResult.IoError
            var totalBytes = 0L
            input.use { stream ->
                FileOutputStream(tempFile).use { out ->
                    val buffer = ByteArray(COPY_BUFFER_SIZE)
                    while (true) {
                        val read = stream.read(buffer)
                        if (read <= 0) break
                        totalBytes += read
                        if (totalBytes > MAX_FILE_SIZE_BYTES) {
                            runCatching { tempFile.delete() }
                            return CopyResult.TooLarge
                        }
                        out.write(buffer, 0, read)
                    }
                    out.flush()
                }
            }

            if (totalBytes <= 0L) {
                runCatching { tempFile.delete() }
                return CopyResult.InvalidFormat
            }

            // Validate format and header decodability before overwriting the live asset
            val decoded = decodeDrawableSync(tempFile.absolutePath, MAX_DECODE_LONG_EDGE_PX)
            if (decoded == null) {
                runCatching { tempFile.delete() }
                return CopyResult.InvalidFormat
            }
            if (decoded is AnimatedImageDrawable) {
                runCatching { decoded.stop() }
            }

            if (targetFile.exists()) {
                runCatching { targetFile.delete() }
            }
            if (!tempFile.renameTo(targetFile)) {
                tempFile.copyTo(targetFile, overwrite = true)
                runCatching { tempFile.delete() }
            }

            return CopyResult.Success(
                filePath = targetFile.absolutePath,
                version = System.currentTimeMillis()
            )
        } catch (_: Throwable) {
            runCatching { tempFile.delete() }
            return CopyResult.IoError
        }
    }

    /**
     * Deletes the internal custom media copy cleanly.
     */
    fun deleteCustomMedia(context: Context, path: String? = null) {
        runCatching {
            if (!path.isNullOrBlank()) {
                val explicit = File(path)
                if (explicit.exists()) explicit.delete()
            }
            val defaultFile = File(
                File(context.applicationContext.filesDir, CUSTOM_MEDIA_DIR),
                CUSTOM_MEDIA_FILE_NAME
            )
            if (defaultFile.exists()) defaultFile.delete()
        }
    }

    /**
     * Synchronously decodes a GIF, Animated WebP, PNG, or JPEG from internal storage with
     * mandatory aspect-ratio-preserving downsampling to [maxLongEdgePx].
     */
    fun decodeDrawableSync(
        filePath: String,
        maxLongEdgePx: Int = MAX_DECODE_LONG_EDGE_PX
    ): Drawable? {
        if (filePath.isBlank()) return null
        val file = File(filePath)
        if (!file.exists() || !file.isFile) return null
        val fileLen = file.length()
        if (fileLen <= 0L || fileLen > MAX_FILE_SIZE_BYTES) return null

        return runCatching {
            val source = ImageDecoder.createSource(file)
            val drawable = ImageDecoder.decodeDrawable(source) { decoder, info, _ ->
                val srcW = info.size.width.coerceAtLeast(1)
                val srcH = info.size.height.coerceAtLeast(1)
                val longEdge = max(srcW, srcH)
                if (longEdge > maxLongEdgePx) {
                    val scale = maxLongEdgePx.toFloat() / longEdge.toFloat()
                    val targetW = (srcW * scale).roundToInt().coerceAtLeast(1)
                    val targetH = (srcH * scale).roundToInt().coerceAtLeast(1)
                    decoder.setTargetSize(targetW, targetH)
                }
                decoder.allocator = ImageDecoder.ALLOCATOR_DEFAULT
            }
            if (drawable is AnimatedImageDrawable) {
                drawable.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
                drawable.stop()
            }
            drawable
        }.getOrNull()
    }

    /**
     * Asynchronously decodes a custom media drawable on a dedicated daemon IO thread and
     * delivers the result on the main thread.
     */
    fun decodeDrawableAsync(
        filePath: String,
        maxLongEdgePx: Int = MAX_DECODE_LONG_EDGE_PX,
        onLoaded: (Drawable?) -> Unit
    ) {
        if (filePath.isBlank()) {
            mainHandler.post { onLoaded(null) }
            return
        }
        decodeExecutor.execute {
            val drawable = decodeDrawableSync(filePath, maxLongEdgePx)
            mainHandler.post { onLoaded(drawable) }
        }
    }
}
