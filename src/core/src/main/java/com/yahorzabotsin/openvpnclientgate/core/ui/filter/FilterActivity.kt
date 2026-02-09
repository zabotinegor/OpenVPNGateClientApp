package com.yahorzabotsin.openvpnclientgate.core.ui.filter

import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.yahorzabotsin.openvpnclientgate.core.R
import com.yahorzabotsin.openvpnclientgate.core.databinding.ActivityTemplateBinding
import com.yahorzabotsin.openvpnclientgate.core.databinding.ContentFilterBinding
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.yahorzabotsin.openvpnclientgate.core.ui.common.navigation.TemplatePage
import com.yahorzabotsin.openvpnclientgate.core.ui.common.text.UiText
import com.yahorzabotsin.openvpnclientgate.core.ui.common.utils.TvUtils
import kotlinx.coroutines.launch
import org.koin.androidx.viewmodel.ext.android.viewModel

class FilterActivity : AppCompatActivity() {
    private lateinit var templateBinding: ActivityTemplateBinding
    private lateinit var binding: ContentFilterBinding

    private val pages = mutableSetOf<FilterPageFragment>()
    private val lastFocusedPositions: MutableMap<AppCategory, Int> = mutableMapOf()
    private var isTvMode: Boolean = false
    private var lastState: FilterUiState? = null
    private val viewModel: FilterViewModel by viewModel()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        templateBinding = TemplatePage.create(this, R.string.menu_filter, null)
        binding = ContentFilterBinding.inflate(layoutInflater, templateBinding.contentContainer, true)
        isTvMode = TvUtils.isTvDevice(this)
        setupPager()
        observeViewModel()
    }

    private fun setupPager() {
        binding.pager.adapter = FilterPagerAdapter(this)
        
        if (isTvMode) {
            binding.pager.isUserInputEnabled = false
        }
        binding.categoryTabs.isFocusable = false
        binding.categoryTabs.isFocusableInTouchMode = false
        binding.categoryTabs.descendantFocusability = FOCUS_BLOCK_DESCENDANTS
        binding.categoryTabs.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO

        fun handleTabSelection(tab: TabLayout.Tab) {
            val category = tab.tag as? AppCategory ?: AppCategory.USER
            viewModel.onAction(FilterAction.SelectCategory(category))
        }

        TabLayoutMediator(binding.categoryTabs, binding.pager) { tab, position ->
            val category = if (position == 0) AppCategory.USER else AppCategory.SYSTEM
            tab.text = getString(if (category == AppCategory.USER) R.string.filter_tab_user else R.string.filter_tab_system)
            tab.tag = category
        }.attach()
        binding.categoryTabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                handleTabSelection(tab)
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab) {
                handleTabSelection(tab)
            }
        })
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (isTvMode) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    // Switch to previous tab (USER)
                    if (binding.pager.currentItem > 0) {
                        binding.pager.currentItem = binding.pager.currentItem - 1
                        return true
                    }
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    // Switch to next tab (SYSTEM)
                    val adapter = binding.pager.adapter
                    if (adapter != null && binding.pager.currentItem < adapter.itemCount - 1) {
                        binding.pager.currentItem = binding.pager.currentItem + 1
                        return true
                    }
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    internal fun registerPage(page: FilterPageFragment) {
        pages.add(page)
        val state = lastState ?: viewModel.state.value
        val isCurrent = state.currentCategory == page.category
        val items = state.itemsByCategory[page.category].orEmpty()
        page.render(items, state.isLoading, isCurrent, lastFocusedPositions[page.category])
    }

    internal fun unregisterPage(page: FilterPageFragment) {
        pages.remove(page)
    }

    internal fun onSelectAllFromPage(category: AppCategory, isChecked: Boolean) {
        viewModel.onAction(FilterAction.SelectAll(category, isChecked))
    }

    internal fun onAppToggleFromPage(packageName: String, isEnabled: Boolean) {
        viewModel.onAction(FilterAction.ToggleApp(packageName, isEnabled))
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                launch {
                    viewModel.state.collect { render(it) }
                }
                launch {
                    viewModel.effects.collect { handleEffect(it) }
                }
            }
        }
    }

    private fun render(state: FilterUiState) {
        lastState = state
        val loading = state.isLoading
        val current = state.currentCategory
        pages.forEach { page ->
            val isCurrent = current == page.category
            val items = state.itemsByCategory[page.category].orEmpty()
            page.render(items, loading, isCurrent, lastFocusedPositions[page.category])
        }
    }

    private fun handleEffect(effect: FilterEffect) {
        when (effect) {
            is FilterEffect.ShowToast -> Toast.makeText(this, resolve(effect.text), Toast.LENGTH_SHORT).show()
        }
    }

    private fun resolve(text: UiText): String = when (text) {
        is UiText.Plain -> text.value
        is UiText.Res -> getString(text.resId, *text.args.toTypedArray())
    }

    internal fun onItemFocusFromPage(category: AppCategory, position: Int) {
        if (position >= 0) lastFocusedPositions[category] = position
    }

    private class FilterPagerAdapter(activity: FragmentActivity) : FragmentStateAdapter(activity) {
        override fun getItemCount(): Int = 2
        override fun createFragment(position: Int): Fragment =
            FilterPageFragment.newInstance(if (position == 0) AppCategory.USER else AppCategory.SYSTEM)
    }

}


