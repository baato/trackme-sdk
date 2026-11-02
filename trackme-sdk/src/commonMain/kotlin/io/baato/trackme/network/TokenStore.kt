@file:OptIn(ExperimentalAtomicApi::class)

package io.baato.trackme.network

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** Thread-safe holder for the current access token, in memory only. The host app decides persistence. */
internal class TokenStore(initial: String) {
    private val ref = AtomicReference<String?>(initial)
    fun current(): String? = ref.load()
    fun set(token: String) = ref.store(token)
}
