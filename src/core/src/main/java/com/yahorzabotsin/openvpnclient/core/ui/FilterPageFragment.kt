package com.yahorzabotsin.openvpnclient.core.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.yahorzabotsin.openvpnclient.core.databinding.FragmentFilterPageBinding

class FilterPageFragment : Fragment() {

    private var _binding: FragmentFilterPageBinding? = null
    private val binding get() = _binding!!
    private lateinit var adapter: FilterListAdapter
    internal lateinit var category: AppCategory

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        category = arguments?.getString(ARG_CATEGORY)?.let { AppCategory.valueOf(it) } ?: AppCategory.USER
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFilterPageBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        adapter = FilterListAdapter(
            onSelectAllToggle = { isChecked -> (activity as? FilterActivity)?.onSelectAllFromPage(category, isChecked) },
            onAppToggle = { pkg, enabled -> (activity as? FilterActivity)?.onAppToggleFromPage(pkg, enabled) }
        )
        binding.appsList.layoutManager = LinearLayoutManager(requireContext())
        binding.appsList.adapter = adapter
        binding.appsList.addItemDecoration(MarginItemDecoration(dpToPx(8)))
        binding.appsList.itemAnimator = null
        (activity as? FilterActivity)?.registerPage(this)
    }

    internal fun render(items: List<FilterListAdapter.Item>, loading: Boolean) {
        if (!isAdded || _binding == null) return
        binding.loadingIndicator.visibility = if (loading) View.VISIBLE else View.GONE
        binding.appsList.visibility = if (loading) View.INVISIBLE else View.VISIBLE
        adapter.submitList(items)
    }

    override fun onDestroyView() {
        (activity as? FilterActivity)?.unregisterPage(this)
        _binding = null
        super.onDestroyView()
    }

    private fun dpToPx(dp: Int): Int =
        (dp * resources.displayMetrics.density).toInt().coerceAtLeast(1)

    companion object {
        private const val ARG_CATEGORY = "category"
        fun newInstance(category: AppCategory): FilterPageFragment =
            FilterPageFragment().apply {
                arguments = Bundle().apply { putString(ARG_CATEGORY, category.name) }
            }
    }
}
