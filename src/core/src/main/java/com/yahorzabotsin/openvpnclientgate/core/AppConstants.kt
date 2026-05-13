package com.yahorzabotsin.openvpnclientgate.core

import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

object ApiConstants {
    val PRIMARY_SERVERS_URL: String = BuildConfig.PRIMARY_SERVERS_URL
    val FALLBACK_SERVERS_URL: String = BuildConfig.FALLBACK_SERVERS_URL

    fun primaryRetrofitBaseUrl(): String =
        PrimaryDomainRoutes.retrofitBaseUrl(PRIMARY_SERVERS_URL)
            ?: error("PRIMARY_SERVERS_URL is invalid: $PRIMARY_SERVERS_URL")

    fun primaryLegacyServersUrl(): String =
        PrimaryDomainRoutes.legacyServersUrl(PRIMARY_SERVERS_URL)
            ?: error("PRIMARY_SERVERS_URL is invalid: $PRIMARY_SERVERS_URL")

    fun primaryVersionByNumberAndBuildUrl(
        versionName: String,
        buildNumber: Long,
        locale: String?
    ): String = PrimaryDomainRoutes.versionByNumberAndBuildUrl(
        baseUrl = PRIMARY_SERVERS_URL,
        versionName = versionName,
        buildNumber = buildNumber,
        locale = locale
    ) ?: error("PRIMARY_SERVERS_URL is invalid: $PRIMARY_SERVERS_URL")

    fun primaryUpdateCheckUrls(
        platform: String,
        releaseType: String,
        currentBuild: Long,
        locale: String?
    ): List<String> = listOfNotNull(
        PrimaryDomainRoutes.updateCheckUrl(
            baseUrl = PRIMARY_SERVERS_URL,
            apiVersion = 2,
            platform = platform,
            releaseType = releaseType,
            currentBuild = currentBuild,
            locale = locale
        ),
        PrimaryDomainRoutes.updateCheckUrl(
            baseUrl = PRIMARY_SERVERS_URL,
            apiVersion = 1,
            platform = platform,
            releaseType = releaseType,
            currentBuild = currentBuild,
            locale = locale
        )
    ).distinct()
}

object PrimaryDomainRoutes {
    private val API_VERSION_MARKER = Regex("/api/v\\d+/", RegexOption.IGNORE_CASE)

    private data class BaseParts(
        val scheme: String,
        val authority: String,
        val pathPrefix: String
    )

    fun retrofitBaseUrl(baseUrl: String): String? {
        val baseParts = resolveBaseParts(baseUrl) ?: return null
        val normalizedPath = if (baseParts.pathPrefix.isBlank()) {
            "/"
        } else {
            baseParts.pathPrefix.trimEnd('/') + "/"
        }
        return buildAbsoluteUrl(
            scheme = baseParts.scheme,
            authority = baseParts.authority,
            path = normalizedPath
        )
    }

    fun legacyServersUrl(baseUrl: String): String? =
        buildUrl(baseUrl, "api/v1/servers/active")

    fun v2CountriesUrl(baseUrl: String): String? =
        buildUrl(baseUrl, "api/v2/servers/countries/active")

    fun v2ServersUrl(baseUrl: String): String? =
        buildUrl(baseUrl, "api/v2/servers")

    fun versionByNumberAndBuildUrl(
        baseUrl: String,
        versionName: String,
        buildNumber: Long,
        locale: String?
    ): String? = buildUrl(
        baseUrl = baseUrl,
        relativePath = "api/v1/versions/number/${encodePathSegment(versionName)}/build/$buildNumber",
        queryParams = listOf("locale" to locale?.trim()?.takeIf { it.isNotBlank() })
    )

    fun updateCheckUrl(
        baseUrl: String,
        apiVersion: Int,
        platform: String,
        releaseType: String,
        currentBuild: Long,
        locale: String?
    ): String? = buildUrl(
        baseUrl = baseUrl,
        relativePath = "api/v$apiVersion/versions/check-update",
        queryParams = listOf(
            "platform" to platform,
            "releaseType" to releaseType,
            "currentBuild" to currentBuild.toString(),
            "locale" to locale?.trim()?.takeIf { it.isNotBlank() }
        )
    )

    private fun buildUrl(
        baseUrl: String,
        relativePath: String,
        queryParams: List<Pair<String, String?>> = emptyList()
    ): String? {
        val baseParts = resolveBaseParts(baseUrl) ?: return null
        val query = queryParams
            .mapNotNull { (name, value) ->
                value?.takeIf { it.isNotBlank() }?.let {
                    "${encodeQueryComponent(name)}=${encodeQueryComponent(it)}"
                }
            }
            .joinToString("&")
        return buildAbsoluteUrl(
            scheme = baseParts.scheme,
            authority = baseParts.authority,
            path = joinPaths(baseParts.pathPrefix, relativePath),
            query = query.takeIf { it.isNotBlank() }
        )
    }

    private fun resolveBaseParts(baseUrl: String): BaseParts? {
        val uri = runCatching { URI(baseUrl.trim()) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        val authority = uri.rawAuthority ?: return null
        if (scheme.isBlank() || authority.isBlank()) return null
        return BaseParts(
            scheme = scheme,
            authority = authority,
            pathPrefix = extractBasePathPrefix(uri.rawPath.orEmpty())
        )
    }

    private fun extractBasePathPrefix(encodedPath: String): String {
        val markerIndex = API_VERSION_MARKER.find(encodedPath)?.range?.first ?: -1
        val rawPrefix = if (markerIndex >= 0) {
            encodedPath.substring(0, markerIndex)
        } else {
            encodedPath
        }.trimEnd('/')

        if (rawPrefix.isBlank()) return ""
        return if (rawPrefix.startsWith('/')) rawPrefix else "/$rawPrefix"
    }

    private fun joinPaths(prefix: String, relativePath: String): String {
        val normalizedRelative = relativePath.trimStart('/').trim()
        if (normalizedRelative.isBlank()) {
            return if (prefix.isBlank()) "/" else prefix.trimEnd('/') + "/"
        }

        return if (prefix.isBlank()) {
            "/$normalizedRelative"
        } else {
            prefix.trimEnd('/') + "/$normalizedRelative"
        }
    }

    private fun buildAbsoluteUrl(
        scheme: String,
        authority: String,
        path: String,
        query: String? = null
    ): String {
        val normalizedPath = if (path.startsWith('/')) path else "/$path"
        return buildString {
            append(scheme)
            append("://")
            append(authority)
            append(normalizedPath)
            if (!query.isNullOrBlank()) {
                append('?')
                append(query)
            }
        }
    }

    private fun encodePathSegment(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.toString()).replace("+", "%20")

    private fun encodeQueryComponent(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.toString()).replace("+", "%20")
}

