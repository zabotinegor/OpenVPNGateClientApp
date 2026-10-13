package com.yahorzabotsin.openvpnclientgate.core.servers

import android.util.Base64
import java.security.MessageDigest

/**
 * Stable per-pool identity for [StoredServer] entries.
 *
 * Positions in the persisted server list are NOT stable: a background refresh
 * ([SelectedCountryStore.saveSelectionPreservingIndex]) can add, remove or reorder entries while an
 * auto-switch cycle is running. Anything that must survive such a refresh (the cycle's visited set,
 * the persisted "current server" index) therefore tracks these keys instead of numeric positions.
 *
 * Scheme, per entry:
 *  - base key: `id:<id>` when the backend server id is valid (> 0). Every server-list path already
 *    treats a positive id as the server's unique identity (de-duplication keeps one entry per id).
 *  - otherwise `ep:<endpoint>|<ip>`, where the endpoint is the connection target declared by the
 *    OpenVPN config (`proto` plus every `remote host port proto`). It does not depend on the rest of
 *    the config text, so a refresh that re-issues an otherwise unchanged server's config keeps its
 *    identity, while same-IP servers on different ports/protocols stay distinct. Never the IP alone.
 *  - when no endpoint can be read from the config, `cfg:<SHA-256 of the config>|<ip>`: a
 *    collision-resistant digest, so two different configs can never share a base key.
 *  - the n-th (n >= 1) additional entry with the same base key gets the ordinal suffix `#n`. Such
 *    entries target the same connection endpoint (or carry the same config), so which of them holds
 *    which ordinal is immaterial; the first occurrence keeps the plain base key, so it does not change
 *    when a duplicate is later added.
 *
 * Trade-off for `ep:` duplicates: entries with the same IP and endpoint may still differ in other
 * config text (certificates, options), but they target the same socket, so the same OpenVPN server.
 * Their `#n` ordinals follow list order, and a reorder can swap them; that only moves a visited mark
 * between two profiles of one server. Ordering the ordinals by config content instead would make
 * them swap whenever the config is re-issued, which the endpoint key exists to tolerate.
 */
internal object ServerIdentity {
    private val REMOTE_LINE = Regex("(?m)^\\s*remote\\s+\\S")
    private val WHITESPACE = Regex("\\s+")

    fun keys(servers: List<StoredServer>): List<String> {
        val seen = HashMap<String, Int>()
        return servers.map { server ->
            val base = baseKey(server)
            val ordinal = (seen[base] ?: 0)
            seen[base] = ordinal + 1
            if (ordinal == 0) base else "$base#$ordinal"
        }
    }

    private fun baseKey(server: StoredServer): String {
        if (server.id > 0) return "id:${server.id}"
        val ip = server.ip.orEmpty()
        val endpoint = endpoint(server.config)
        return if (endpoint != null) "ep:$endpoint|$ip" else "cfg:${sha256(server.config)}|$ip"
    }

    /**
     * The connection target declared by [config] (stored base64-encoded, or plain text), or null
     * when it declares no `remote`. Normalised so that line order and letter case do not matter.
     */
    internal fun endpoint(config: String): String? {
        val text = configText(config) ?: return null
        var proto = ""
        var port = ""
        val remotes = sortedSetOf<String>()
        text.lineSequence().forEach { line ->
            val tokens = line.trim().split(WHITESPACE)
            when (tokens.first().lowercase()) {
                "proto" -> proto = tokens.getOrNull(1)?.lowercase().orEmpty()
                "port" -> port = tokens.getOrNull(1).orEmpty()
                "remote" -> tokens.getOrNull(1)?.let { host ->
                    remotes += "${host.lowercase()}:${tokens.getOrNull(2).orEmpty()}:${tokens.getOrNull(3)?.lowercase().orEmpty()}"
                }
            }
        }
        if (remotes.isEmpty()) return null
        return "$proto:$port@${remotes.joinToString(",")}"
    }

    private fun configText(config: String): String? {
        if (config.isBlank()) return null
        if (REMOTE_LINE.containsMatchIn(config)) return config
        val decoded = try {
            String(Base64.decode(config, Base64.DEFAULT), Charsets.UTF_8)
        } catch (_: IllegalArgumentException) {
            return null
        }
        return decoded.takeIf { REMOTE_LINE.containsMatchIn(it) }
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}

/** A cycle candidate resolved against the persisted list: the server, its identity and position. */
data class CycleCandidate(val server: StoredServer, val key: String, val index: Int)
