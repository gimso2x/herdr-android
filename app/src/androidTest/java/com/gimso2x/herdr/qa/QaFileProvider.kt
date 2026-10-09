package com.gimso2x.herdr.qa

import android.content.Context
import android.content.pm.ProviderInfo
import android.util.Base64
import androidx.core.content.FileProvider
import java.io.File

/** The C1 fixture file: a real 1x1 PNG, identical bytes for the provider and the test. */
object QaPng {
    const val NAME = "qa.png"
    const val AUTHORITY = "com.gimso2x.herdr.test.fileprovider"
    private const val B64 =
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg=="

    fun bytes(): ByteArray = Base64.decode(B64, Base64.DEFAULT)
}

/**
 * A FileProvider declared in the androidTest manifest. The test APK has its own uid, so the
 * provider itself writes the fixture into its own cache dir on start-up (the instrumentation code
 * runs as the app and cannot write there). It is exported so the app's WebView can read the URI
 * returned by the stubbed chooser without a URI grant; FileProvider refuses `exported=true`
 * itself, so the flag is cleared on the copy it validates.
 */
class QaFileProvider : FileProvider() {
    override fun attachInfo(context: Context, info: ProviderInfo) {
        val copy = ProviderInfo(info)
        copy.exported = false
        super.attachInfo(context, copy)
    }

    override fun onCreate(): Boolean {
        val ctx = context ?: return false
        val dir = File(ctx.cacheDir, "qa").apply { mkdirs() }
        File(dir, QaPng.NAME).writeBytes(QaPng.bytes())
        return super.onCreate()
    }
}
