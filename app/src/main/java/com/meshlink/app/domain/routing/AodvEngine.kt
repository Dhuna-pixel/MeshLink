package com.meshlink.app.domain.routing

import com.meshlink.app.domain.models.MessagePacket
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class RouteControl(
    val kind: String, val origin: String, val destination: String = "",
    val requestId: String = UUID.randomUUID().toString(), val sequence: Long = 0,
    val destinationSequence: Long = -1, val hops: Int = 0, val ttl: Int = 16,
    val name: String = "", val unreachable: Map<String, Long> = emptyMap()
)

data class AodvRoute(val destination: String, val nextHop: String, val hops: Int,
    val sequence: Long, var expires: Long, var valid: Boolean = true,
    val precursors: MutableSet<String> = mutableSetOf())

/** Pure, serialized-by-monitor AODV subset. Only destinations answer RREQs.
 * Sequence numbers use nonnegative Long values; IDs are scoped to a process UUID.
 * Data TTL/hop metadata changes exactly once on receipt of a link traversal.
 */
class AodvEngine(
    val id: String, private var name: String,
    private val transmit: (String, MessagePacket) -> Boolean,
    private val delivered: (MessagePacket) -> Unit,
    private val status: (String, String) -> Unit,
    private val learned: (String, String) -> Unit,
    private val log: (String) -> Unit,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val acknowledged: (String, List<String>) -> Unit = { _, _ -> }
) {
    private val neighbors = mutableSetOf<String>()
    private val routes = linkedMapOf<String, AodvRoute>()
    private val seenRequests = linkedMapOf<String, Long>()
    private val seenData = linkedMapOf<String, Long>()
    private val seenAnnouncements = linkedMapOf<String, Long>()
    private data class Pending(val packet: MessagePacket, val started: Long, var attempt: Int = 0, var retryAt: Long = 0)
    private val pending = linkedMapOf<String, Pending>()
    private var sequence = now().coerceAtLeast(0)
    private var lastAnnouncement = 0L
    @Synchronized fun rename(displayName: String) { name = displayName; if (neighbors.isNotEmpty()) announce() }
    @Synchronized fun cancelPending(reason: String) { pending.keys.toList().forEach { status(it, reason) }; pending.clear() }
    @Synchronized fun snapshot() = routes.values.map { it.copy(valid = it.valid && it.expires > now(), precursors = it.precursors.toMutableSet()) }
    private fun route(dest: String) = routes[dest]?.takeIf { it.valid && it.expires > now() && it.nextHop in neighbors }
    private fun remember(cache: MutableMap<String, Long>, key: String): Boolean {
        cache.entries.removeAll { it.value <= now() }
        if (cache.containsKey(key)) return false
        if (cache.size >= 2048) cache.remove(cache.keys.first())
        cache[key] = now() + 120_000
        return true
    }
    private fun install(dest: String, via: String, hops: Int, seq: Long) {
        if (dest == id || via !in neighbors || hops !in 1..16 || seq < 0) return
        val old = routes[dest]
        if (old == null || seq > old.sequence || (seq == old.sequence && (!old.valid || hops < old.hops || (hops == old.hops && via == old.nextHop)))) {
            if (routes.size >= 512 && old == null) routes.remove(routes.keys.first())
            routes[dest] = AodvRoute(dest, via, hops, seq, now() + 60_000, precursors = old?.precursors ?: mutableSetOf())
            log("Route installed: $dest via $via ($hops hops, sequence $seq)")
        }
    }
    private fun control(c: RouteControl, peer: String? = null, except: String? = null) {
        val packet = MessagePacket("CTRL-${c.requestId}-${c.kind}", id, name, c.destination, payload = "", control = c)
        if (peer != null) transmit(peer, packet) else neighbors.filter { it != except }.forEach { transmit(it, packet) }
    }
    @Synchronized fun neighborUp(peer: String) { neighbors.add(peer); announce() }
    @Synchronized fun neighborDown(peer: String) {
        neighbors.remove(peer)
        val broken = routes.values.filter { it.valid && it.nextHop == peer }
        broken.forEach { it.valid = false }
        val unreachable = broken.associate { it.destination to (it.sequence + 1) }
        broken.forEach { routes[it.destination] = it.copy(sequence = unreachable.getValue(it.destination), valid = false) }
        if (unreachable.isNotEmpty()) {
            log("RERR generated: link to $peer lost")
            broken.flatMap { it.precursors }.toSet().filter { it in neighbors }.forEach {
                control(RouteControl("RERR", id, unreachable = unreachable), it)
            }
        }
        pending.values.forEach { it.retryAt = 0 }
    }
    private fun announce() {
        lastAnnouncement = now()
        control(RouteControl("ANNOUNCE", id, name = name, ttl = 8))
    }
    @Synchronized fun send(packet: MessagePacket) {
        if (pending.size >= 64) { status(packet.messageId, "Failed: outgoing queue full"); return }
        pending[packet.messageId] = Pending(packet, now())
        status(packet.messageId, "Discovering route")
        tick()
    }
    @Synchronized fun tick() {
        routes.values.filter { it.expires <= now() }.forEach { it.valid = false }
        if (now() - lastAnnouncement >= 30_000 && neighbors.isNotEmpty()) announce()
        val discovering = mutableSetOf<String>()
        for ((key, p) in pending.toMap()) {
            if (now() - p.started >= 35_000 || (p.attempt >= 3 && now() >= p.retryAt)) {
                pending.remove(key); status(key, "Failed: delivery acknowledgment timed out"); continue
            }
            if (now() < p.retryAt) continue
            val r = route(p.packet.destinationId)
            if (r != null) {
                if (transmit(r.nextHop, p.packet)) status(key, "Queued for next hop; awaiting destination ACK")
                else neighborDown(r.nextHop)
                p.attempt++; p.retryAt = now() + 10_000
            } else if (discovering.add(p.packet.destinationId)) {
                sequence++
                val c = RouteControl("RREQ", id, p.packet.destinationId, sequence = sequence,
                    destinationSequence = routes[p.packet.destinationId]?.sequence ?: -1)
                remember(seenRequests, "$id:${c.requestId}")
                control(c); log("RREQ generated for ${c.destination}")
                p.attempt++; p.retryAt = now() + 5_000
            }
        }
    }
    @Synchronized fun receive(packet: MessagePacket, from: String) {
        if (from !in neighbors) return
        val c = packet.control
        if (c != null) {
            if (c.ttl !in 1..16 || c.hops !in 0..15 || c.origin.length !in 1..80 || c.sequence < 0) return
            when (c.kind) {
                "ANNOUNCE" -> if (c.origin != id && remember(seenAnnouncements, c.requestId)) {
                    learned(c.origin, c.name.take(64)); if (c.ttl > 1) control(c.copy(ttl = c.ttl - 1), except = from)
                }
                "RREQ" -> {
                    if (c.origin == id || !remember(seenRequests, "${c.origin}:${c.requestId}")) { log("Duplicate RREQ rejected"); return }
                    install(c.origin, from, c.hops + 1, c.sequence)
                    if (c.destination == id) {
                        sequence = maxOf(sequence, c.destinationSequence) + 1
                        control(RouteControl("RREP", c.origin, id, sequence = sequence), from)
                    } else if (c.ttl > 1) { control(c.copy(hops = c.hops + 1, ttl = c.ttl - 1), except = from); log("RREQ forwarded") }
                }
                "RREP" -> {
                    install(c.destination, from, c.hops + 1, c.sequence)
                    if (c.origin == id) { pending.values.filter { it.packet.destinationId == c.destination }.forEach { it.retryAt = 0; it.attempt = 0 }; tick() }
                    else route(c.origin)?.let { reverse ->
                        if (reverse.nextHop != from && c.ttl > 1) {
                            routes[c.destination]?.precursors?.add(reverse.nextHop)
                            reverse.precursors.add(from)
                            control(c.copy(hops = c.hops + 1, ttl = c.ttl - 1), reverse.nextHop)
                        }
                    }
                }
                "RERR" -> {
                    val affected = routes.values.filter { it.valid && it.nextHop == from && (c.unreachable[it.destination] ?: -1) >= it.sequence }
                    affected.forEach { it.valid = false; routes[it.destination] = it.copy(sequence = c.unreachable.getValue(it.destination)) }
                    if (c.ttl > 1) affected.flatMap { it.precursors }.toSet().filter { it != from && it in neighbors }.forEach { control(c.copy(ttl = c.ttl - 1), it) }
                    pending.values.forEach { if (route(it.packet.destinationId) == null) it.retryAt = 0 }
                    log("RERR received; ${affected.size} routes invalidated")
                }
            }
            return
        }
        if (packet.ttl <= 0 || packet.ttl > 32 || packet.hopCount !in 0..31 || id in packet.routeHistory) { log("TTL expired or routing loop rejected"); return }
        val arrived = packet.createHopPacket(id)
        if (packet.destinationId == id) {
            if (packet.ackFor != null) {
                val p = pending[packet.ackFor]
                if (p != null && p.packet.destinationId == packet.sourceId) { pending.remove(packet.ackFor); acknowledged(packet.ackFor, packet.returnPath.reversed()); status(packet.ackFor, "Delivered (destination ACK)") }
            } else {
                if (remember(seenData, packet.messageId)) delivered(arrived) else log("Duplicate data rejected")
                val ack = MessagePacket(UUID.randomUUID().toString(), id, name, packet.sourceId, payload = "", ttl = 16,
                    routeHistory = listOf(id), ackFor = packet.messageId, returnPath = arrived.routeHistory.reversed())
                transmit(from, ack)
            }
            return
        }
        if (!remember(seenData, packet.messageId)) { log("Duplicate data rejected"); return }
        // Permit bounded source retransmission after the ACK timeout while suppressing immediate duplicates.
        seenData[packet.messageId] = now() + 8_000
        if (arrived.ttl <= 0) { log("TTL expired"); return }
        if (packet.ackFor != null) {
            val index = packet.returnPath.indexOf(id)
            packet.returnPath.getOrNull(index + 1)?.takeIf { index >= 0 && it in neighbors && it != from }?.let { transmit(it, arrived) }
        } else {
            val r = route(packet.destinationId)
            if (r != null && r.nextHop != from && r.nextHop !in arrived.routeHistory) {
                r.precursors.add(from)
                if (!transmit(r.nextHop, arrived)) neighborDown(r.nextHop)
                log("Message forwarded to ${r.nextHop}")
            } else control(RouteControl("RERR", id, unreachable = mapOf(packet.destinationId to ((routes[packet.destinationId]?.sequence ?: 0) + 1))), from)
        }
    }
}
