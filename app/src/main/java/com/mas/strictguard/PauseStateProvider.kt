package com.mas.strictguard

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri

/**
 * Hanya-baca: memberi tahu aplikasi pendamping (Telegram Channel Blocker)
 * apakah Penjaga Ketat sedang Mode OFF (jeda). Hanya mengembalikan satu angka
 * (1 = sedang jeda, 0 = tidak). Tidak ada data lain yang dibuka.
 * content://com.mas.strictguard.state/paused
 */
class PauseStateProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?
    ): Cursor {
        val ctx = context
        val paused = try { ctx != null && Store.pausedNow(ctx) } catch (e: Exception) { false }
        return MatrixCursor(arrayOf("paused")).apply { addRow(arrayOf<Any>(if (paused) 1 else 0)) }
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
