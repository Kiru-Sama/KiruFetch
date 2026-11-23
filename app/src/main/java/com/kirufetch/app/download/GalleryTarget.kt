package com.kirufetch.app.download

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * 把下载成品写进系统媒体库，让相册/文件管理能直接看到。
 *
 * - Android 10+：走 MediaStore 的 Movies/KiruFetch，IS_PENDING 占位再放行，
 *   不需要任何存储权限，也不会被当成"应用私有文件"隐藏。
 * - Android 8/9：直接写公共 Movies 目录并用 MediaScanner 通知媒体库，
 *   需要 WRITE_EXTERNAL_STORAGE。
 */
object GalleryTarget {

    private const val PREFIX = "content://media/"
    const val RELATIVE_DIR = "Movies/KiruFetch"

    fun isGalleryPath(path: String?): Boolean = path?.startsWith(PREFIX) == true

    fun mimeOf(fileName: String): String = when {
        fileName.endsWith(".webm", true) -> "video/webm"
        fileName.endsWith(".mkv", true) -> "video/x-matroska"
        fileName.endsWith(".m4a", true) -> "audio/mp4"
        fileName.endsWith(".mp3", true) -> "audio/mpeg"
        fileName.endsWith(".mp4", true) -> "video/mp4"
        else -> "application/octet-stream"
    }

    /** 成功返回可供播放的路径（content:// 或文件路径），失败返回 null 由调用方保留原文件。 */
    fun publish(context: Context, source: File, fileName: String): String? {
        val mime = mimeOf(fileName)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            publishModern(context, source, fileName, mime)
        } else {
            publishLegacy(context, source, fileName, mime)
        }
    }

    private fun publishModern(context: Context, source: File, fileName: String, mime: String): String? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Video.Media.MIME_TYPE, mime)
            put(MediaStore.Video.Media.RELATIVE_PATH, RELATIVE_DIR)
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = runCatching {
            resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
        }.getOrNull() ?: return null

        return try {
            resolver.openOutputStream(uri)?.use { out ->
                source.inputStream().use { input -> input.copyTo(out, 128 * 1024) }
            } ?: throw IllegalStateException("openOutputStream 返回 null")

            val done = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
            uri.toString()
        } catch (t: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            null
        }
    }

    private fun publishLegacy(context: Context, source: File, fileName: String, mime: String): String? {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
            "KiruFetch"
        )
        if (!dir.exists() && !dir.mkdirs()) return null
        var target = File(dir, fileName)
        var index = 1
        while (target.exists()) {
            target = File(dir, "${fileName.substringBeforeLast('.')}($index).${fileName.substringAfterLast('.')}")
            index++
        }
        return try {
            source.inputStream().use { input ->
                target.outputStream().use { out -> input.copyTo(out, 128 * 1024) }
            }
            MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf(mime), null)
            target.absolutePath
        } catch (t: Throwable) {
            runCatching { target.delete() }
            null
        }
    }

    fun delete(context: Context, path: String?): Boolean {
        if (!isGalleryPath(path)) return false
        return runCatching {
            context.contentResolver.delete(Uri.parse(path), null, null) > 0
        }.getOrDefault(false)
    }

    fun exists(context: Context, path: String?): Boolean {
        if (!isGalleryPath(path)) return false
        return runCatching {
            context.contentResolver.openAssetFileDescriptor(Uri.parse(path), "r")?.use { true } ?: false
        }.getOrDefault(false)
    }
}
