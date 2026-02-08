package com.yahorzabotsin.openvpnclientgate.core.filter

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DefaultAppFilterRepository(
    context: Context
) : AppFilterRepository {
    private val appContext = context.applicationContext
    private val packageManager = appContext.packageManager

    override suspend fun loadInstalledApps(): List<AppFilterEntry> = withContext(Dispatchers.Default) {
        val packages = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getInstalledApplications(0)
            }
        } catch (_: Exception) {
            emptyList<ApplicationInfo>()
        }

        val self = appContext.packageName
        packages
            .filter { it.packageName != self }
            .mapNotNull { appInfo ->
                val launchIntent = packageManager.getLaunchIntentForPackage(appInfo.packageName)
                if (launchIntent == null) return@mapNotNull null

                val label = try {
                    packageManager.getApplicationLabel(appInfo)?.toString()
                } catch (_: Exception) {
                    null
                } ?: appInfo.packageName

                AppFilterEntry(
                    packageName = appInfo.packageName,
                    label = label,
                    isSystemApp = isSystemApp(appInfo)
                )
            }
    }

    override fun loadExcludedPackages(): Set<String> =
        AppFilterStore.loadExcludedPackages(appContext)

    override fun saveExcludedPackages(packages: Set<String>) {
        AppFilterStore.saveExcludedPackages(appContext, packages)
    }

    override fun updateExcludedPackages(update: (MutableSet<String>) -> Unit): Set<String> =
        AppFilterStore.updateExcludedPackages(appContext, update)

    private fun isSystemApp(info: ApplicationInfo): Boolean {
        val flags = info.flags
        return (flags and ApplicationInfo.FLAG_SYSTEM) != 0 ||
                (flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
    }
}
