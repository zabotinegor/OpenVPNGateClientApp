package com.yahorzabotsin.openvpnclientgate.core.updates

data class AppUpdateAsset(
    val id: Int,
    val name: String,
    val platform: String,
    val buildNumber: Long?,
    val assetType: String,
    val sizeBytes: Long,
    val contentHash: String,
    val downloadProxyUrl: String
)

data class AppUpdateInfo(
    val hasUpdate: Boolean,
    val currentBuild: Long,
    val latestBuild: Long?,
    val latestVersion: String?,
    val name: String,
    val changelog: String,
    val resolvedLocale: String?,
    val message: String,
    val asset: AppUpdateAsset?
)
