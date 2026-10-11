package com.gimso2x.herdr.qa

import android.util.Base64

/** The C1 fixture file as served by [QaFileProvider]. */
object QaPng {
    const val NAME = QaFileProvider.NAME
    /** `<test applicationId>.fileprovider`: the test APK of the debug build is com.gimso2x.herdr.debug.test. */
    val AUTHORITY: String get() = Qa.instr.context.packageName + QaFileProvider.AUTHORITY_SUFFIX

    fun bytes(): ByteArray = Base64.decode(QaFileProvider.PNG_B64, Base64.DEFAULT)
}
