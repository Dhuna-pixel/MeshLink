package com.meshlink.app.domain.network

import com.meshlink.app.domain.models.Node
import com.meshlink.app.domain.routing.AodvRoute

data class DestinationOption(val id: String, val name: String, val status: String, val canMessage: Boolean = true, val blocked: Boolean = false, val about: String = "", val verified: Boolean = false)
object Destinations {
    fun list(localId: String, connected: List<Node>, known: List<Node>, routes: List<AodvRoute>, now: Long): List<DestinationOption> {
        val direct = connected.map { it.nodeId }.toSet()
        return (connected + known).distinctBy { it.nodeId }.filter { it.nodeId != localId }.map { node ->
            val route = routes.find { it.destination == node.nodeId && it.valid && it.expires > now }
            val label = when {
                node.nodeId in direct -> "Directly connected"
                route != null -> "Route available (${route.hops} hops)"
                now - node.lastSeenTimestamp > 90_000 -> "Stale — reachability unknown"
                else -> "Known remote node — route discovery on send"
            }
            DestinationOption(node.nodeId, node.nodeName, label)
        }.sortedWith(compareBy<DestinationOption> { it.id !in direct }.thenBy { it.name }.thenBy { it.id })
    }
    fun everyone(options: List<DestinationOption>, recent: Set<String>): Set<String> =
        options.filter { it.canMessage && it.id in recent }.map { it.id }.toSet()

    fun retainedSelections(selected: Set<String>, options: List<DestinationOption>): Set<String> =
        selected.intersect(options.filter { it.canMessage }.map { it.id }.toSet())

    fun toggleSelection(selected: Set<String>, id: String, options: List<DestinationOption>): Set<String> {
        val retained = retainedSelections(selected, options)
        return if (options.none { it.id == id && it.canMessage }) retained
        else if (id in retained) retained - id else retained + id
    }

    fun retainedSelection(selected: String, options: List<DestinationOption>) = selected.takeIf { id -> options.any { it.id == id } } ?: ""
}
