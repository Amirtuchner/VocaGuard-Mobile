package io.vocaguard.billing

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
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

    private val _billingError = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val billingError: SharedFlow<String> = _billingError.asSharedFlow()

    /** Localized pricing line for the paywall (e.g. "1 month free · then $4.99/month"),
     *  built from Play's ProductDetails so it always matches Play Console config. */
    private val _priceLine = MutableStateFlow<String?>(null)
    val priceLine: StateFlow<String?> = _priceLine.asStateFlow()

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
        // Debug builds can't purchase (Play only sells to Play-installed builds),
        // so the paywall would permanently lock developers out — skip it.
        if (io.vocaguard.BuildConfig.DEBUG) {
            _status.value = SubscriptionStatus.Active
            queryProductDetails()?.let { _priceLine.value = formatPriceLine(it) }
            return
        }
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.SUBS)
            .build()
        val result = billingClient.queryPurchasesAsync(params)
        val active = result.purchasesList.any { purchase ->
            purchase.products.contains(PRODUCT_ID) &&
                purchase.purchaseState == Purchase.PurchaseState.PURCHASED
        }
        if (active) {
            _status.value = SubscriptionStatus.Active
        } else {
            // Only block with paywall if the product actually exists in Play Console.
            // If it hasn't been created yet, stay Unknown so access isn't blocked.
            val details = queryProductDetails()
            details?.let { _priceLine.value = formatPriceLine(it) }
            _status.value = if (details != null) SubscriptionStatus.Expired else SubscriptionStatus.Unknown
        }

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

    private suspend fun queryProductDetails() =
        billingClient.queryProductDetails(
            QueryProductDetailsParams.newBuilder().setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(PRODUCT_ID)
                        .setProductType(BillingClient.ProductType.SUBS)
                        .build()
                )
            ).build()
        ).productDetailsList?.firstOrNull()

    /** Prefer an offer with a free phase (free trial) when the user is eligible;
     *  Play only returns offers the user qualifies for, so returning users
     *  simply fall back to the base plan. */
    private fun bestOffer(details: ProductDetails): ProductDetails.SubscriptionOfferDetails? {
        val offers = details.subscriptionOfferDetails ?: return null
        return offers.firstOrNull { offer ->
            offer.pricingPhases.pricingPhaseList.any { it.priceAmountMicros == 0L }
        } ?: offers.firstOrNull()
    }

    private fun formatPriceLine(details: ProductDetails): String? {
        val phases = bestOffer(details)?.pricingPhases?.pricingPhaseList ?: return null
        val paid = phases.lastOrNull { it.priceAmountMicros > 0 } ?: return null
        val trial = phases.firstOrNull { it.priceAmountMicros == 0L }
        val perMonth = "${paid.formattedPrice}/month"
        return if (trial != null) {
            val trialLength = when (trial.billingPeriod) {
                "P1M" -> "1 month"
                "P1W" -> "1 week"
                "P3D" -> "3 days"
                "P7D" -> "7 days"
                else -> trial.billingPeriod.removePrefix("P").lowercase()
            }
            "$trialLength free · then $perMonth · cancel any time"
        } else {
            "$perMonth · cancel any time"
        }
    }

    fun startSubscriptionFlow(activity: Activity) {
        scope.launch(Dispatchers.Main) {
            val productDetails = queryProductDetails()
            if (productDetails == null) {
                _billingError.tryEmit("Subscription product not available yet. Please try again later.")
                return@launch
            }
            val offerToken = bestOffer(productDetails)?.offerToken
            if (offerToken == null) {
                _billingError.tryEmit("No subscription offer available. Please try again later.")
                return@launch
            }

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
