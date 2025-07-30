package com.github.libretube.ui.sheets

import android.os.Bundle
import android.view.View
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.activityViewModels
import com.github.libretube.R
import com.github.libretube.databinding.SheetSubscriptionsBinding
import com.github.libretube.ui.adapters.SubscriptionChannelAdapter
import com.github.libretube.ui.extensions.onSystemInsets
import com.github.libretube.ui.models.SubscriptionsViewModel

class SubscriptionsBottomSheet : ExpandedBottomSheet(R.layout.sheet_subscriptions) {
    private var _binding: SheetSubscriptionsBinding? = null
    private val binding get() = _binding!!
    private val adapter = SubscriptionChannelAdapter()

    private val viewModel: SubscriptionsViewModel by activityViewModels()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding = SheetSubscriptionsBinding.bind(view)
        super.onViewCreated(view, savedInstanceState)

        binding.channelsRecycler.adapter = adapter

        // add bottom padding to the list, to ensure that the last item is not overlapped by the system bars
        binding.channelsRecycler.onSystemInsets { v, systemInsets ->
            v.setPadding(
                v.paddingLeft,
                v.paddingTop,
                v.paddingRight,
                systemInsets.bottom
            )
        }

        binding.subscriptionsSearchInput.addTextChangedListener { query ->
            showFilteredSubscriptions(adapter, query.toString())
        }

        viewModel.subscriptions.observe(viewLifecycleOwner) {
            showFilteredSubscriptions(adapter, binding.subscriptionsSearchInput.text.toString())
        }
    }

    private fun showFilteredSubscriptions(adapter: SubscriptionChannelAdapter, query: String) {
        val loweredQuery = query.lowercase()

        val filteredSubscriptions = viewModel.subscriptions.value.orEmpty()
            .filter { it.name.lowercase().contains(loweredQuery) }
        adapter.submitList(filteredSubscriptions)
    }
}