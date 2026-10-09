package com.gimso2x.herdr.shell

import com.gimso2x.herdr.access.Clock

/** Release stub: no hooks exist, so the refresh loop always uses the real clock and delay. */
object AccessTestHooks {
    val sleeper: (suspend (Long) -> Unit)? get() = null
    val clock: Clock? get() = null
}
