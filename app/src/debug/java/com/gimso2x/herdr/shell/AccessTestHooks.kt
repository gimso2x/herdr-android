package com.gimso2x.herdr.shell

import com.gimso2x.herdr.access.Clock

/**
 * Debug builds only: instrumentation replaces the refresh loop's time sources before launching
 * MainActivity. Release builds get a no-op stub (src/release) so main code compiles unchanged.
 */
object AccessTestHooks {
    @Volatile
    var sleeper: (suspend (Long) -> Unit)? = null

    @Volatile
    var clock: Clock? = null

    fun reset() {
        sleeper = null
        clock = null
    }
}
