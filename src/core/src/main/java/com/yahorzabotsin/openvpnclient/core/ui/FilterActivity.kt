package com.yahorzabotsin.openvpnclient.core.ui

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.yahorzabotsin.openvpnclient.core.R
import com.yahorzabotsin.openvpnclient.core.databinding.ContentFilterBinding
import com.yahorzabotsin.openvpnclient.core.filter.AppFilterEntry
import com.yahorzabotsin.openvpnclient.core.filter.AppFilterStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

enum class AppCategory { USER, SYSTEM }

class FilterActivity : BaseTemplateActivity(R.string.menu_filter) {

    private lateinit var binding: ContentFilterBinding

    private var allApps: List<AppFilterEntry> = emptyList()
    private var excludedPackages: Set<String> = emptySet()
    private var currentCategory: AppCategory = AppCategory.USER
    private val pages = mutableSetOf<FilterPageFragment>()
    private var isLoading = false

    override fun inflateContent(inflater: LayoutInflater, container: ViewGroup) {
        binding = ContentFilterBinding.inflate(inflater, container, true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupPager()
        loadApps()
    }

    private fun setupPager() {
        binding.pager.adapter = FilterPagerAdapter(this)
        TabLayoutMediator(binding.categoryTabs, binding.pager) { tab, position ->
            val category = if (position == 0) AppCategory.USER else AppCategory.SYSTEM
            tab.text = getString(if (category == AppCategory.USER) R.string.filter_tab_user else R.string.filter_tab_system)
            tab.tag = category
        }.attach()
        binding.categoryTabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                currentCategory = tab.tag as? AppCategory ?: AppCategory.USER
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab) {
                currentCategory = tab.tag as? AppCategory ?: AppCategory.USER
            }
        })
    }

    private fun loadApps() {
        lifecycleScope.launch {
            setLoading(true)
            val installedApps = withContext(Dispatchers.Default) { queryInstalledApps() }
            val installedPackageNames = installedApps.map { it.packageName }.toSet()

            var storedExcluded = AppFilterStore.loadExcludedPackages(this@FilterActivity)
            val cleaned = storedExcluded.intersect(installedPackageNames)
            if (cleaned.size != storedExcluded.size) {
                AppFilterStore.saveExcludedPackages(this@FilterActivity, cleaned)
                storedExcluded = cleaned
            }

            allApps = installedApps.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
            excludedPackages = storedExcluded
            notifyPages()
            setLoading(false)
        }
    }

    private fun itemsFor(category: AppCategory): List<FilterListAdapter.Item> {
        val appEntries = allApps.filter { it.isSystemApp == (category == AppCategory.SYSTEM) }
        val appItems = appEntries.map {
            FilterListAdapter.Item.App(it, !excludedPackages.contains(it.packageName))
        }
        val allEnabled = appItems.isNotEmpty() && appItems.all { it.isEnabled }
        return buildList {
            add(FilterListAdapter.Item.Info)
            add(FilterListAdapter.Item.SelectAll(allEnabled, appItems.isNotEmpty()))
            addAll(appItems)
        }
    }

    private fun currentItems(): List<AppFilterEntry> = allApps.filter {
        it.isSystemApp == (currentCategory == AppCategory.SYSTEM)
    }

    private fun onAppToggleChanged(packageName: String, isEnabled: Boolean) {
        excludedPackages = AppFilterStore.updateExcludedPackages(this) { set ->
            if (isEnabled) set.remove(packageName) else set.add(packageName)
        }
        notifyPages()
    }

    private fun onSelectAllToggle(isChecked: Boolean) {
        applySelectAll(isChecked)
    }

    private fun applySelectAll(isChecked: Boolean) {
        val targetPackages = currentItems().map { it.packageName }
        excludedPackages = AppFilterStore.updateExcludedPackages(this) { set ->
            if (isChecked) {
                set.removeAll(targetPackages.toSet())
            } else {
                set.addAll(targetPackages)
            }
        }
        notifyPages()
    }

    private fun setLoading(loading: Boolean) {
        isLoading = loading
        notifyPages()
    }

    private fun queryInstalledApps(): List<AppFilterEntry> {
        val pm = packageManager
        val packages = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getInstalledApplications(0)
            }
        } catch (_: Exception) {
            emptyList<ApplicationInfo>()
        }

        val self = packageName
        return packages
            .filter { it.packageName != self }
            .mapNotNull { appInfo ->
                val launchIntent = pm.getLaunchIntentForPackage(appInfo.packageName)
                if (launchIntent == null) return@mapNotNull null

                val label = try {
                    pm.getApplicationLabel(appInfo)?.toString()
                } catch (_: Exception) {
                    null
                } ?: appInfo.packageName

                val icon = try {
                    pm.getApplicationIcon(appInfo)
                } catch (_: Exception) {
                    null
                }

                if (icon == null) return@mapNotNull null

                AppFilterEntry(
                    packageName = appInfo.packageName,
                    label = label,
                    isSystemApp = isSystemApp(appInfo),
                    icon = icon
                )
            }
    }

    private fun isSystemApp(info: ApplicationInfo): Boolean {
        val flags = info.flags
        return (flags and ApplicationInfo.FLAG_SYSTEM) != 0 ||
                (flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
    }

    private fun dpToPx(dp: Int): Int =
        (dp * resources.displayMetrics.density).roundToInt().coerceAtLeast(1)

    internal fun registerPage(page: FilterPageFragment) {
        pages.add(page)
        page.render(itemsFor(page.category), isLoading)
    }

    internal fun unregisterPage(page: FilterPageFragment) {
        pages.remove(page)
    }

    internal fun onSelectAllFromPage(category: AppCategory, isChecked: Boolean) {
        currentCategory = category
        applySelectAll(isChecked)
    }

    internal fun onAppToggleFromPage(packageName: String, isEnabled: Boolean) {
        onAppToggleChanged(packageName, isEnabled)
    }

    private fun notifyPages() {
        val loading = isLoading
        pages.forEach { page ->
            page.render(itemsFor(page.category), loading)
        }
    }

    private class FilterPagerAdapter(activity: FragmentActivity) : FragmentStateAdapter(activity) {
        override fun getItemCount(): Int = 2
        override fun createFragment(position: Int): Fragment =
            FilterPageFragment.newInstance(if (position == 0) AppCategory.USER else AppCategory.SYSTEM)
    }
}
