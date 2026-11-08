package com.abubadr.arribha

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.provider.Settings
import android.util.Log
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Free for 48 hours from the start of the first game, then a one-time lifetime purchase through Google Play ("arribha_lifetime").
 *  - The purchase belongs to the player's Google account: it comes back on any TV and after reinstalling.
 *  - The trial's start is kept on our server (by a one-way hash of this TV's id) with the server's clock, so
 *    clearing the app's data, reinstalling or changing the TV's clock does not restart or stretch it.
 */
object Licensing {
    const val PRODUCT = "arribha_lifetime"
    const val TRIAL_MS = 48 * 3600_000L
    private const val SERVER = "https://arribha-intake.d-fifn1.workers.dev/trial"

    private lateinit var app: Context
    private var billing: BillingClient? = null
    private var product: ProductDetails? = null
    @Volatile var owned = false; private set
    @Volatile var price: String? = null; private set
    var onChange: (() -> Unit)? = null

    private val prefs get() = app.getSharedPreferences("arribha_license", Context.MODE_PRIVATE)
    /** when the trial started, on the server's clock (0 = no game played yet) */
    private val started get() = prefs.getLong("started", 0L)
    private val serverNow get() = System.currentTimeMillis() + prefs.getLong("offset", 0L)
    val trialStarted get() = started > 0
    val trialLeftSeconds: Long get() =
        if (!trialStarted) TRIAL_MS / 1000 else ((started + TRIAL_MS - serverNow) / 1000).coerceAtLeast(0)
    val canPlay get() = owned || trialLeftSeconds > 0

    fun init(ctx: Context) {
        if (::app.isInitialized) return
        app = ctx.applicationContext
        owned = prefs.getBoolean("owned", false)
        syncTrial(false)
        billing = BillingClient.newBuilder(app)
            .setListener { r, purchases -> if (r.responseCode == BillingClient.BillingResponseCode.OK) purchases?.forEach(::handle) }
            .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
            .build()
        connect()
    }

    private fun connect() {
        billing?.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(r: BillingResult) {
                if (r.responseCode != BillingClient.BillingResponseCode.OK) return
                restore()
                billing?.queryProductDetailsAsync(QueryProductDetailsParams.newBuilder().setProductList(listOf(
                    QueryProductDetailsParams.Product.newBuilder().setProductId(PRODUCT).setProductType(BillingClient.ProductType.INAPP).build()
                )).build()) { _, res: List<ProductDetails> ->
                    product = res.firstOrNull()
                    price = product?.oneTimePurchaseOfferDetails?.formattedPrice
                    onChange?.invoke()
                }
            }
            override fun onBillingServiceDisconnected() {}
        })
    }

    /** purchases made earlier (other TV, reinstall) come back automatically */
    private fun restore() {
        billing?.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build()) { r, list ->
            if (r.responseCode != BillingClient.BillingResponseCode.OK) return@queryPurchasesAsync   // offline: keep what we know
            list.forEach(::handle)
            // Google no longer lists the purchase (refunded or cancelled): it is not owned any more
            if (owned && list.none { PRODUCT in it.products && it.purchaseState == Purchase.PurchaseState.PURCHASED }) {
                owned = false; prefs.edit().putBoolean("owned", false).apply(); onChange?.invoke()
            }
        }
    }

    private fun handle(p: Purchase) {
        if (PRODUCT !in p.products || p.purchaseState != Purchase.PurchaseState.PURCHASED) return
        owned = true
        prefs.edit().putBoolean("owned", true).apply()
        if (!p.isAcknowledged) billing?.acknowledgePurchase(
            AcknowledgePurchaseParams.newBuilder().setPurchaseToken(p.purchaseToken).build()) {}
        onChange?.invoke()
    }

    /** opens Google Play's own payment sheet; returns false if the store is not ready */
    fun buy(a: Activity): Boolean {
        val d = product ?: run { connect(); return false }
        val r = billing?.launchBillingFlow(a, BillingFlowParams.newBuilder().setProductDetailsParamsList(listOf(
            BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(d).build())).build())
        return r?.responseCode == BillingClient.BillingResponseCode.OK
    }

    /** called when a game starts streaming (and every minute after): the first call starts the 48 hours */
    fun addPlayed(@Suppress("UNUSED_PARAMETER") seconds: Int) {
        if (owned || trialStarted) return
        prefs.edit().putLong("started", serverNow).apply()       // until the server answers
        syncTrial(true)
        onChange?.invoke()
    }

    private fun syncTrial(start: Boolean) {
        Thread {
            runCatching {
                val c = URL(SERVER).openConnection() as HttpURLConnection
                c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 4000; c.readTimeout = 6000
                c.setRequestProperty("Content-Type", "application/json"); c.setRequestProperty("User-Agent", "arribha-app/1")
                c.outputStream.use { it.write(JSONObject().put("id", deviceId()).put("start", start).toString().toByteArray()) }
                val r = JSONObject(c.inputStream.bufferedReader().readText())
                val e = prefs.edit().putLong("offset", r.getLong("now") - System.currentTimeMillis())
                val st = r.optLong("started", 0L)
                if (st > 0) e.putLong("started", st)                    // the server's record always wins
                e.apply(); onChange?.invoke()
            }.onFailure { Log.w("Arribha", "trial sync: $it") }
        }.start()
    }

    /** one-way hash of this TV's Android id (stable across reinstall and cleared data; not personal data) */
    @SuppressLint("HardwareIds")
    private fun deviceId(): String {
        val raw = Settings.Secure.getString(app.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
        return MessageDigest.getInstance("SHA-256").digest("arribha|$raw".toByteArray()).take(16).joinToString("") { "%02x".format(it) }
    }
}
