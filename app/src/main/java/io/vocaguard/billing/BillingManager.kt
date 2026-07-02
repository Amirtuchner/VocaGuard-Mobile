package io.vocaguard.billing

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.acknowledgePurchase
import com.android.billingclient.api.queryProductDetails
import com.android.billingclient.api.queryPurchasesAsync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class SubscriptionStatus {
    /** Subscription is active (including free trial). */
    object Active : SubscriptionStatus()
    /** No active subscription — show paywall. */
    object Expired : SubscriptionStatus()
    /** Billing client not yet connected; grant access to avoid blocking on slow networks. */
    object Unknown : SubscriptionStatus()
}

class BillingManager private constructor(private val context: Context) :
    PurchasesUpdatedListener {

    companion object {
        const val PRODUCT_ID = "vocaguard_monthly"

        @Volatile private var instance: BillingManager? = null
        fun getInstance(context: Context): BillingManager =
            instance ?: synchronized(this) {
                instance ?: BillingManager(context.applicationContext).also { instance = it }
            }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _status = MutableStateFlow<SubscriptionStatus>(SubscriptionStatus.Unknown)
    val status: StateFlow<SubscriptionStatus> = _status.asStateFlow()

    private val billingClient: BillingClient = BillingClient.newBuilder(context)
        .setListener(this)
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder().enableOneTimeProducts().build()
        )
        .build()

    init {
        connect()
    }

    private fun connect() {
        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    scope.launch { refreshStatus() }
                }
            }
            override fun onBillingServiceDisconnected() {
                // Will reconnect on next refresh() call
            }
        })
    }

    private suspend fun refreshStatus() {
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.SUBS)
            .build()
        val result = billingClient.queryPurchasesAsync(params)
        val active = result.purchasesList.any { purchase ->
            purchase.products.contains(PRODUCT_ID) &&
                purchase.purchaseState == Purchase.PurchaseState.PURCHASED
        }
        _status.value = if (active) SubscriptionStatus.Active else SubscriptionStatus.Expired

        // Acknowledge any unacknowledged purchases so Play doesn't revoke them
        result.purchasesList
            .filter { !it.isAcknowledged && it.purchaseState == Purchase.PurchaseState.PURCHASED }
            .forEach { acknowledge(it) }
    }

    private suspend fun acknowledge(purchase: Purchase) {
        val params = AcknowledgePurchaseParams.newBuilder()
            .setPurchaseToken(purchase.purchaseToken)
            .build()
        billingClient.acknowledgePurchase(params)
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: List<Purchase>?) {
        if (result.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
            val active = purchases.any { purchase ->
                purchase.products.contains(PRODUCT_ID) &&
                    purchase.purchaseState == Purchase.PurchaseState.PURCHASED
            }
            if (active) _status.value = SubscriptionStatus.Active
            scope.launch {
                purchases
                    .filter { !it.isAcknowledged && it.purchaseState == Purchase.PurchaseState.PURCHASED }
                    .forEach { acknowledge(it) }
            }
        }
    }

    fun startSubscriptionFlow(activity: Activity) {
        scope.launch(Dispatchers.Main) {
            val productList = listOf(
                QueryProductDetailsParams.Product.newBuilder()
                    .setProductId(PRODUCT_ID)
                    .setProductType(BillingClient.ProductType.SUBS)
                    .build()
            )
            val detailsResult = billingClient.queryProductDetails(
                QueryProductDetailsParams.newBuilder().setProductList(productList).build()
            )
            val productDetails = detailsResult.productDetailsList?.firstOrNull() ?: return@launch
            val offerToken = productDetails.subscriptionOfferDetails?.firstOrNull()?.offerToken
                ?: return@launch

            val flowParams = BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(
                    listOf(
                        BillingFlowParams.ProductDetailsParams.newBuilder()
                            .setProductDetails(productDetails)
                            .setOfferToken(offerToken)
                            .build()
                    )
                )
                .build()
            billingClient.launchBillingFlow(activity, flowParams)
        }
    }

    /** Re-query subscription state — call from onResume to catch external cancellations. */
    fun refresh() {
        if (billingClient.isReady) {
            scope.launch { refreshStatus() }
        } else {
            connect()
        }
    }
}
