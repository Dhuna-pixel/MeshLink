package com.meshlink.app.data.transport

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.withTimeout

/** Shared production/test boundary for Android's asynchronous completion callback. */
internal suspend fun awaitGattWrite(completion: Deferred<Int>): Int = withTimeout(8_000) { completion.await() }
