package com.wxplain.app.ingest

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.Process

class KeyProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        val ctx = context ?: return null
        if (!callerAllowed()) return null
        val hex = values?.getAsString("key")?.trim().orEmpty()
        if (hex.length < 4 || hex.length > 128) return null
        if (!hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        val hash = values?.getAsString("hash")?.trim().orEmpty()
        KeyStore.save(ctx, hex, hash = hash)
        return uri
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        if (Binder.getCallingUid() != Process.myUid()) return null
        val ctx = context ?: return null
        val cursor = MatrixCursor(arrayOf("hex", "password"))
        val hex = KeyStore.hex(ctx)
        val pwd = KeyStore.password(ctx)
        if (hex != null && pwd != null) cursor.addRow(arrayOf(hex, pwd))
        return cursor
    }

    override fun getType(uri: Uri): String? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

    private fun callerAllowed(): Boolean {
        val ctx = context ?: return false
        val uid = Binder.getCallingUid()
        if (uid == Process.myUid()) return true
        return try {
            ctx.packageManager.getPackagesForUid(uid)?.contains("com.tencent.mm") == true
        } catch (_: Exception) {
            false
        }
    }
}
