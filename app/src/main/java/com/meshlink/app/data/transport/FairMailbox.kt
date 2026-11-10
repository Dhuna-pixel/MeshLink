package com.meshlink.app.data.transport

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CancellationException

/** Serialized SSLEngine input ordering is preserved within each lane. No parallel TLS access. */
internal class FairMailbox<T>(private val lane: (T) -> Int) {
    private val queues = Array(3) { ArrayDeque<T>() }
    private val limits = intArrayOf(232,16,8)
    private val schedule = intArrayOf(0,1,0,2,0,1)
    private var cursor = 0
    private var closed = false
    private val wake = Channel<Unit>(Channel.CONFLATED)
    @Synchronized fun offer(value: T): Boolean {
        val index=lane(value); require(index in 0..2)
        if(closed || queues[index].size >= limits[index]) return false
        queues[index].addLast(value); wake.trySend(Unit); return true
    }
    @Synchronized private fun poll(): T? {
        repeat(schedule.size) {
            val q=queues[schedule[cursor]]; cursor=(cursor+1)%schedule.size
            if(q.isNotEmpty()) return q.removeFirst()
        }
        if(closed) throw CancellationException("Mailbox closed")
        return null
    }
    suspend fun receive(): T {
        while(true) { poll()?.let { return it }; if (wake.receiveCatching().isClosed) throw CancellationException("Mailbox closed") }
    }
    @Synchronized fun close() { closed=true; queues.forEach { it.clear() }; wake.close() }
}
