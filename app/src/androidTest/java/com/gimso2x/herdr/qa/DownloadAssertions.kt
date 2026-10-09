package com.gimso2x.herdr.qa

import android.app.DownloadManager
import android.database.Cursor

/** DownloadManager queries shared by the download scenarios (L4, C5). */
object DownloadAssertions {
    data class Row(val id: Long, val status: Int, val mime: String?, val uri: String?, val title: String?)

    private fun rows(manager: DownloadManager): List<Row> {
        val cursor: Cursor = manager.query(DownloadManager.Query())
        cursor.use {
            val out = ArrayList<Row>()
            while (it.moveToNext()) {
                out += Row(
                    id = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)),
                    status = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                    mime = it.getString(it.getColumnIndexOrThrow(DownloadManager.COLUMN_MEDIA_TYPE)),
                    uri = it.getString(it.getColumnIndexOrThrow(DownloadManager.COLUMN_URI)),
                    title = it.getString(it.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE)),
                )
            }
            return out
        }
    }

    fun removeAll(manager: DownloadManager) {
        val ids = rows(manager).map { it.id }.toLongArray()
        if (ids.isNotEmpty()) manager.remove(*ids)
    }

    /** Waits (bounded) for the download whose URI ends with [pathSuffix] to reach STATUS_SUCCESSFUL. */
    fun awaitSuccessful(manager: DownloadManager, pathSuffix: String, timeoutMs: Long): Row {
        var found: Row? = null
        Qa.until(timeoutMs, "download $pathSuffix successful") {
            val row = rows(manager).firstOrNull { it.uri?.endsWith(pathSuffix) == true }
            if (row?.status == DownloadManager.STATUS_FAILED) {
                throw AssertionError("download $pathSuffix failed")
            }
            found = row?.takeIf { it.status == DownloadManager.STATUS_SUCCESSFUL }
            found != null
        }
        return found!!
    }

    fun read(manager: DownloadManager, id: Long): ByteArray =
        android.os.ParcelFileDescriptor.AutoCloseInputStream(manager.openDownloadedFile(id)).use { it.readBytes() }
}
