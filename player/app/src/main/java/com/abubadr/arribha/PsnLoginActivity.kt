package com.abubadr.arribha

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * The player signs in to PlayStation Network on the TV itself; the app reads their Account ID from the answer.
 * Uses the same public Remote Play sign-in as Chiaki. The password goes only to Sony; nothing is sent to us.
 */
class PsnLoginActivity : Activity() {
    companion object {
        const val EXTRA_ACCOUNT_ID = "account_id"
        private const val CLIENT_ID = "ba495a24-818c-472b-b12d-ff231c1b5745"
        private const val CLIENT_SECRET = "mvaiZkRsAsI1IBkY"
        private const val REDIRECT = "https://remoteplay.dl.playstation.net/remoteplay/redirect"
        private const val TOKEN_URL = "https://auth.api.sonyentertainmentnetwork.com/2.0/oauth/token"
        private val LOGIN_URL = "https://auth.api.sonyentertainmentnetwork.com/2.0/oauth/authorize?service_entity=urn:service-entity:psn" +
                "&response_type=code&client_id=$CLIENT_ID&redirect_uri=$REDIRECT&scope=psn:clientapp&request_locale=ar&ui=pr" +
                "&service_logo=ps&layout_type=popup&smcid=remoteplay&prompt=always&PlatformPrivacyWs1=minimal&"
    }

    private var done = false

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        CookieManager.getInstance().removeAllCookies(null)
        val web = WebView(this)
        web.setBackgroundColor(Color.rgb(12, 16, 28))
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean = catchRedirect(req.url)
            @Deprecated("old devices") override fun shouldOverrideUrlLoading(view: WebView, url: String) = catchRedirect(Uri.parse(url))
        }
        setContentView(web)
        web.loadUrl(LOGIN_URL)
    }

    private fun catchRedirect(uri: Uri): Boolean {
        if (!uri.toString().startsWith(REDIRECT)) return false
        val code = uri.getQueryParameter("code") ?: return true
        if (done) return true
        done = true
        Thread {
            val id = runCatching { accountId(code) }.onFailure { Log.e("Arribha", "psn login", it) }.getOrNull()
            runOnUiThread {
                setResult(if (id != null) RESULT_OK else RESULT_CANCELED, Intent().putExtra(EXTRA_ACCOUNT_ID, id))
                finish()
            }
        }.start()
        return true
    }

    /** code -> token -> user id -> Account ID (8 bytes, little endian, base64), as Chiaki does */
    private fun accountId(code: String): String = PsnAuth.accountId(code)
}

object PsnAuth {
    private const val CLIENT_ID = "ba495a24-818c-472b-b12d-ff231c1b5745"
    private const val CLIENT_SECRET = "mvaiZkRsAsI1IBkY"
    private const val REDIRECT = "https://remoteplay.dl.playstation.net/remoteplay/redirect"
    private const val TOKEN_URL = "https://auth.api.sonyentertainmentnetwork.com/2.0/oauth/token"

    fun accountId(code: String): String {
        val auth = "Basic " + Base64.encodeToString("$CLIENT_ID:$CLIENT_SECRET".toByteArray(), Base64.NO_WRAP)
        val body = "grant_type=authorization_code&code=${URLEncoder.encode(code, "UTF-8")}&redirect_uri=$REDIRECT&"
        val tok = JSONObject(http(TOKEN_URL, auth, body)).getString("access_token")
        val info = JSONObject(http("$TOKEN_URL/${URLEncoder.encode(tok, "UTF-8")}", auth, null))
        val uid = info.getString("user_id").toULong()
        val bytes = ByteArray(8) { i -> (uid shr (8 * i)).toByte() }
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private fun http(url: String, auth: String, form: String?): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.setRequestProperty("Authorization", auth)
        c.connectTimeout = 8000; c.readTimeout = 10000
        if (form != null) {
            c.requestMethod = "POST"; c.doOutput = true
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            c.outputStream.use { it.write(form.toByteArray()) }
        }
        return c.inputStream.bufferedReader().use { it.readText() }
    }
}
