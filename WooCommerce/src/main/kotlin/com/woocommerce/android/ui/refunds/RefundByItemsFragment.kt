package com.woocommerce.android.ui.refunds

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.Observer
import androidx.navigation.fragment.findNavController
import androidx.navigation.navGraphViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.woocommerce.android.R
import com.woocommerce.android.analytics.AnalyticsTracker
import com.woocommerce.android.extensions.collapse
import com.woocommerce.android.extensions.expand
import com.woocommerce.android.extensions.takeIfNotEqualTo
import com.woocommerce.android.tools.ProductImageMap
import com.woocommerce.android.ui.base.BaseFragment
import com.woocommerce.android.ui.refunds.IssueRefundViewModel.IssueRefundEvent.ShowNumberPicker
import com.woocommerce.android.util.CurrencyFormatter
import com.woocommerce.android.viewmodel.ViewModelFactory
import kotlinx.android.synthetic.main.fragment_refund_by_items.*
import kotlinx.android.synthetic.main.refund_by_items_products.*
import javax.inject.Inject

class RefundByItemsFragment : BaseFragment() {
    @Inject lateinit var viewModelFactory: ViewModelFactory
    @Inject lateinit var currencyFormatter: CurrencyFormatter
    @Inject lateinit var imageMap: ProductImageMap

    private val viewModel: IssueRefundViewModel by navGraphViewModels(R.id.nav_graph_refunds) { viewModelFactory }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        super.onCreate(savedInstanceState)
        return inflater.inflate(R.layout.fragment_refund_by_items, container, false)
    }

    override fun onResume() {
        super.onResume()
        AnalyticsTracker.trackViewShown(this)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initializeViews()
        setupObservers()
    }

    private fun initializeViews() {
        issueRefund_products.layoutManager = LinearLayoutManager(context)
        issueRefund_products.setHasFixedSize(true)

        issueRefund_selectAllButton.setOnClickListener {
            viewModel.onSelectAllButtonTapped()
        }

        issueRefund_btnNextFromItems.setOnClickListener {
            viewModel.onNextButtonTappedFromItems()
        }

        issueRefund_shippingSwitch.setOnCheckedChangeListener { _, isChecked ->
            viewModel.onRefundItemsShippingSwitchChanged(isChecked)
        }
    }

    private fun setupObservers() {
        viewModel.refundByItemsStateLiveData.observe(viewLifecycleOwner) { old, new ->
            new.currency?.takeIfNotEqualTo(old?.currency) {
                issueRefund_products.adapter = RefundProductListAdapter(
                        currencyFormatter.buildBigDecimalFormatter(new.currency),
                        { productId -> viewModel.onRefundQuantityTapped(productId) },
                        imageMap
                )
            }
            new.items?.takeIfNotEqualTo(old?.items) { list ->
                val adapter = issueRefund_products.adapter as RefundProductListAdapter
                adapter.update(list)

                val selectedItems = list.sumBy { it.quantity }
                issueRefund_btnNextFromItems.isEnabled = selectedItems > 0

                val selectedItemsHeader = getString(R.string.order_refunds_items_selected, selectedItems)
                issueRefund_selectedItems.text = selectedItemsHeader
            }
            new.formattedProductsRefund?.takeIfNotEqualTo(old?.formattedProductsRefund) {
                issueRefund_productsTotal.text = it
            }
            new.taxes?.takeIfNotEqualTo(old?.taxes) {
                issueRefund_taxesTotal.text = it
            }
            new.subtotal?.takeIfNotEqualTo(old?.subtotal) {
                issueRefund_subtotal.text = it
            }
            new.isShippingRefundVisible?.takeIfNotEqualTo(old?.isShippingRefundVisible) { isVisible ->
                if (isVisible) {
                    issueRefund_shippingSection.expand()
                } else {
                    issueRefund_shippingSection.collapse()
                }
            }
        }

        viewModel.event.observe(viewLifecycleOwner, Observer { event ->
            when (event) {
                is ShowNumberPicker -> {
                    val action = IssueRefundFragmentDirections.actionIssueRefundFragmentToRefundItemsPickerDialog(
                            getString(R.string.order_refunds_select_quantity),
                            event.refundItem.product.productId,
                            event.refundItem.product.quantity.toInt(),
                            event.refundItem.quantity
                    )
                    findNavController().navigate(action)
                }
                else -> event.isHandled = false
            }
        })
    }
}
