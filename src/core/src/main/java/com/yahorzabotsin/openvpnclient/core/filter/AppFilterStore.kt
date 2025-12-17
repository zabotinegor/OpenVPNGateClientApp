package com.yahorzabotsin.openvpnclient.core.filter

import android.content.Context
import android.content.SharedPreferences

object AppFilterStore {
    private const val PREFS_NAME = "app_filter"
    private const val KEY_EXCLUDED_PACKAGES = "excluded_packages"

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun loadExcludedPackages(ctx: Context): Set<String> =
        prefs(ctx)
            .getStringSet(KEY_EXCLUDED_PACKAGES, emptySet())
            ?.filter { it.isNotBlank() }
            ?.toSet()
            ?: emptySet()

    fun saveExcludedPackages(ctx: Context, packages: Set<String>) {
        val sanitized = packages
            .mapNotNull { it.takeIf { name -> name.isNotBlank() } }
            .toSet()
        prefs(ctx).edit()
            .putStringSet(KEY_EXCLUDED_PACKAGES, HashSet(sanitized))
            .apply()
    }

    fun updateExcludedPackages(ctx: Context, update: (MutableSet<String>) -> Unit): Set<String> {
        val current = loadExcludedPackages(ctx).toMutableSet()
        update(current)
        saveExcludedPackages(ctx, current)
        return current
    }
}
