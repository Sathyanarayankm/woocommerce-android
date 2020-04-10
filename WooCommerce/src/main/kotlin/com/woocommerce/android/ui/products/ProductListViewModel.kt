package com.woocommerce.android.ui.products

import android.os.Parcelable
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.squareup.inject.assisted.Assisted
import com.squareup.inject.assisted.AssistedInject
import com.woocommerce.android.R
import com.woocommerce.android.analytics.AnalyticsTracker
import com.woocommerce.android.analytics.AnalyticsTracker.Stat
import com.woocommerce.android.annotations.OpenClassOnDebug
import com.woocommerce.android.di.ViewModelAssistedFactory
import com.woocommerce.android.media.ProductImagesService.Companion.OnProductImagesUpdateCompletedEvent
import com.woocommerce.android.model.Product
import com.woocommerce.android.tools.NetworkStatus
import com.woocommerce.android.util.CoroutineDispatchers
import com.woocommerce.android.util.WooLog
import com.woocommerce.android.viewmodel.LiveDataDelegate
import com.woocommerce.android.viewmodel.MultiLiveEvent.Event.ShowSnackbar
import com.woocommerce.android.viewmodel.SavedStateWithArgs
import com.woocommerce.android.viewmodel.ScopedViewModel
import kotlinx.android.parcel.Parcelize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import org.wordpress.android.fluxc.store.WCProductStore.ProductFilterOption

@OpenClassOnDebug
class ProductListViewModel @AssistedInject constructor(
    @Assisted savedState: SavedStateWithArgs,
    dispatchers: CoroutineDispatchers,
    private val productRepository: ProductListRepository,
    private val networkStatus: NetworkStatus
) : ScopedViewModel(savedState, dispatchers) {
    companion object {
        private const val SEARCH_TYPING_DELAY_MS = 500L
        private const val KEY_PRODUCT_FILTER_OPTIONS = "key_product_filter_options"
    }

    private val _productList = MutableLiveData<List<Product>>()
    val productList: LiveData<List<Product>> = _productList

    final val viewStateLiveData = LiveDataDelegate(savedState, ViewState())
    private var viewState by viewStateLiveData

    final val productFilterOptions: Map<ProductFilterOption, String> by lazy {
        val params = savedState.get<Map<ProductFilterOption, String>>(KEY_PRODUCT_FILTER_OPTIONS) ?: emptyMap()
        savedState[KEY_PRODUCT_FILTER_OPTIONS] = params
        params
    }

    private var searchJob: Job? = null
    private var loadJob: Job? = null

    init {
        EventBus.getDefault().register(this)
        if (_productList.value == null) {
            loadProducts()
        }
    }

    override fun onCleared() {
        super.onCleared()
        productRepository.onCleanup()
        EventBus.getDefault().unregister(this)
    }

    fun isSearching() = viewState.isSearchActive == true

    private fun isLoading() = viewState.isLoading == true

    private fun isRefreshing() = viewState.isRefreshing == true

    fun getSearchQuery() = viewState.query

    fun onSearchQueryChanged(query: String) {
        viewState = viewState.copy(query = query, isEmptyViewVisible = false)

        if (query.length > 2) {
            onSearchRequested()
        } else {
            launch {
                searchJob?.cancelAndJoin()

                _productList.value = emptyList()
                viewState = viewState.copy(isEmptyViewVisible = false)
            }
        }
    }

    fun onRefreshRequested() {
        AnalyticsTracker.track(Stat.PRODUCT_LIST_PULLED_TO_REFRESH)
        refreshProducts()
    }

    fun onSearchOpened() {
        _productList.value = emptyList()
        viewState = viewState.copy(isSearchActive = true)
    }

    fun onSearchClosed() {
        launch {
            searchJob?.cancelAndJoin()
            viewState = viewState.copy(query = null, isSearchActive = false, isEmptyViewVisible = false)
            loadProducts()
        }
    }

    fun onLoadMoreRequested() {
        loadProducts(loadMore = true)
    }

    fun onSearchRequested() {
        AnalyticsTracker.track(Stat.PRODUCT_LIST_SEARCHED,
                mapOf(AnalyticsTracker.KEY_SEARCH to viewState.query)
        )
        loadProducts()
    }

    final fun reloadProductsFromDb() {
        _productList.value = productRepository.getProductList(productFilterOptions)
    }

    final fun loadProducts(loadMore: Boolean = false) {
        if (isLoading()) {
            WooLog.d(WooLog.T.PRODUCTS, "already loading products")
            return
        }

        if (loadMore && !productRepository.canLoadMoreProducts) {
            WooLog.d(WooLog.T.PRODUCTS, "can't load more products")
            return
        }

        if (isSearching()) {
            // cancel any existing search, then start a new one after a brief delay so we don't actually perform
            // the fetch until the user stops typing
            searchJob?.cancel()
            searchJob = launch {
                delay(SEARCH_TYPING_DELAY_MS)
                viewState = viewState.copy(
                        isLoading = true,
                        isLoadingMore = loadMore,
                        isSkeletonShown = !loadMore,
                        isEmptyViewVisible = false
                )
                fetchProductList(viewState.query, loadMore = loadMore)
            }
        } else {
            // if a fetch is already active, wait for it to finish before we start another one
            waitForExistingLoad()

            loadJob = launch {
                val showSkeleton: Boolean
                if (loadMore) {
                    showSkeleton = false
                } else {
                    // if this is the initial load, first get the products from the db and show them immediately
                    val productsInDb = productRepository.getProductList(productFilterOptions)
                    if (productsInDb.isEmpty()) {
                        showSkeleton = true
                    } else {
                        _productList.value = productsInDb
                        showSkeleton = isRefreshing()
                    }
                }
                viewState = viewState.copy(
                        isLoading = true,
                        isLoadingMore = loadMore,
                        isSkeletonShown = showSkeleton,
                        isEmptyViewVisible = false
                )
                fetchProductList(loadMore = loadMore)
            }
        }
    }

    /**
     * If products are already being fetched, wait for the existing job to finish
     */
    private fun waitForExistingLoad() {
        if (loadJob?.isActive == true) {
            launch {
                try {
                    loadJob?.join()
                } catch (e: CancellationException) {
                    WooLog.d(WooLog.T.PRODUCTS, "CancellationException while waiting for existing fetch")
                }
            }
        }
    }

    fun refreshProducts() {
        viewState = viewState.copy(isRefreshing = true)
        loadProducts()
    }

    private suspend fun fetchProductList(searchQuery: String? = null, loadMore: Boolean = false) {
        if (networkStatus.isConnected()) {
            if (searchQuery.isNullOrEmpty()) {
                _productList.value = productRepository.fetchProductList(loadMore, productFilterOptions)
            } else {
                productRepository.searchProductList(searchQuery, loadMore)?.let { fetchedProducts ->
                    // make sure the search query hasn't changed while the fetch was processing
                    if (searchQuery == productRepository.lastSearchQuery) {
                        if (loadMore) {
                            _productList.value = _productList.value.orEmpty() + fetchedProducts
                        } else {
                            _productList.value = fetchedProducts
                        }
                    } else {
                        WooLog.d(WooLog.T.PRODUCTS, "Search query changed")
                    }
                }
            }

            viewState = viewState.copy(
                    isLoading = true,
                    canLoadMore = productRepository.canLoadMoreProducts,
                    isEmptyViewVisible = _productList.value?.isEmpty() == true
            )
        } else {
            triggerEvent(ShowSnackbar(R.string.offline_error))
        }

        viewState = viewState.copy(
                isSkeletonShown = false,
                isLoading = false,
                isLoadingMore = false,
                isRefreshing = false
        )
    }

    @Suppress("unused")
    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onEventMainThread(event: OnProductImagesUpdateCompletedEvent) {
        loadProducts()
    }

    @Parcelize
    data class ViewState(
        val isSkeletonShown: Boolean? = null,
        val isLoading: Boolean? = null,
        val isLoadingMore: Boolean? = null,
        val canLoadMore: Boolean? = null,
        val isRefreshing: Boolean? = null,
        val query: String? = null,
        val isSearchActive: Boolean? = null,
        val isEmptyViewVisible: Boolean? = null
    ) : Parcelable

    @AssistedInject.Factory
    interface Factory : ViewModelAssistedFactory<ProductListViewModel>
}
