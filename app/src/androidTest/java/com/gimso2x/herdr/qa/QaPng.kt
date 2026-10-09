package com.gimso2x.herdr.qa

import android.util.Base64

/** The C1 fixture file as served by [QaFileProvider]. */
object QaPng {
    const val NAME = QaFileProvider.NAME
    const val AUTHORITY = QaFileProvider.AUTHORITY

    fun bytes(): ByteArray = Base64.decode(QaFileProvider.PNG_B64, Base64.DEFAULT)
}
