package com.yahorzabotsin.openvpnclientgate.core.filter

interface AppFilterRepository {
    suspend fun loadInstalledApps(): List<AppFilterEntry>
    fun loadExcludedPackages(): Set<String>
    fun saveExcludedPackages(packages: Set<String>)
    fun updateExcludedPackages(update: (MutableSet<String>) -> Unit): Set<String>
}
