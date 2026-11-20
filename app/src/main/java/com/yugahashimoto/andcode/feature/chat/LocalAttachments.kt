package com.yugahashimoto.andcode.feature.chat

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import com.yugahashimoto.andcode.core.api.PromptAttachment

/**
 * Reads a file the user picked and returns it as a prompt attachment. A text model can use text and
 * code files, so those are inlined (up to 200 KB); anything else is refused with a clear message
 * rather than silently dropped.
 */
object LocalAttachments {
    const val MAX_BYTES = 200_000

    fun import(context: Context, uri: Uri): PromptAttachment {
        var name = "attachment.txt"
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = c.getString(it) }
        }
        val mime = context.contentResolver.getType(uri) ?: "text/plain"
        val bytes = context.contentResolver.openInputStream(uri)!!.use { it.readNBytes(MAX_BYTES + 1) }
        require(bytes.size <= MAX_BYTES) { "$name is larger than ${MAX_BYTES / 1000} KB" }
        require(bytes.none { it == 0.toByte() }) { "$name looks like a binary file; the on-device model reads text and code" }
        return PromptAttachment(filename = name, mime = if (mime.startsWith("text/")) mime else "text/plain", url = "data:text/plain;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP))
    }
}
