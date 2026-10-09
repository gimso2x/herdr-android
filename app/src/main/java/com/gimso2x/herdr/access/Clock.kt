package com.gimso2x.herdr.access

/** Injectable wall clock (epoch milliseconds) so refresh timing is testable without sleeping. */
fun interface Clock {
    fun nowMillis(): Long

    companion object {
        val SYSTEM: Clock = Clock { java.lang.System.currentTimeMillis() }
    }
}
