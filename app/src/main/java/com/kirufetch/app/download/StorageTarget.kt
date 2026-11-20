package com.kirufetch.app.download

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File

/**
 * 下载落盘目标。默认是应用专属目录（File 路径）；
 * 用户在设置里选了目录后，下载完成会把成品拷进 SAF 目录，库里记 content:// 。
 */
object StorageTarget {

    private const val DOC_PREFIX = "content://"

    fun isDocumentPath(path: String?): Boolean = path?.startsWith(DOC_PREFIX) == true

    fun displayName(context: Context, treeUri: Uri): String? = runCatching {
        DocumentFile.fromTreeUri(context, treeUri)?.name
    }.getOrNull()

    /** 拷到用户选的目录，成功返回 document uri；失败返回 null 由调用方回退本地文件。 */
    fun export(context: Context, source: File, fileName: String, treeUri: Uri): String? {
        val tree = runCatching { DocumentFile.fromTreeUri(context, treeUri) }.getOrNull() ?: return null
        if (!tree.canWrite()) return null

        val mime = when {
            fileName.endsWith(".webm", ignoreCase = true) -> "video/webm"
            fileName.endsWith(".mp4", ignoreCase = true) -> "video/mp4"
            else -> "application/octet-stream"
        }

        val doc = runCatching { tree.createFile(mime, fileName) }.getOrNull() ?: return null
        val out = runCatching { context.contentResolver.openOutputStream(doc.uri) }.getOrNull()
        if (out == null) {
            runCatching { doc.delete() }
            return null
        }

        return try {
            out.use { target ->
                source.inputStream().use { input -> input.copyTo(target, 128 * 1024) }
            }
            doc.uri.toString()
        } catch (t: Throwable) {
            runCatching { doc.delete() }
            null
        }
    }

    fun exists(context: Context, path: String?): Boolean {
        if (path.isNullOrBlank()) return false
        return if (isDocumentPath(path)) {
            runCatching {
                DocumentFile.fromSingleUri(context, Uri.parse(path))?.exists() == true
            }.getOrDefault(false)
        } else {
            File(path).let { it.exists() && it.length() > 0 }
        }
    }

    fun delete(context: Context, path: String?) {
        if (path.isNullOrBlank()) return
        if (isDocumentPath(path)) {
            runCatching {
                val uri = Uri.parse(path)
                val doc = DocumentFile.fromSingleUri(context, uri)
                doc?.delete() ?: run {
                    android.provider.DocumentsContract.deleteDocument(context.contentResolver, uri)
                    true
                }
            }
        } else {
            runCatching { File(path).takeIf { it.exists() }?.delete() }
        }
    }
}
