package com.gimso2x.herdr.watch

import com.gimso2x.herdr.access.Clock

/** Release stub: no hooks exist, the watcher always uses the real clock. */
object WatchTestHooks {
    val clock: Clock get() = Clock.SYSTEM
}
