package io.vocaguard.service

import io.vocaguard.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * The one HTTP client for the VocaGuard token server.
 *
 * - Normal certificate checking (system CAs: api.vocaguard.com has a Let's Encrypt
 *   cert). This replaces the old trust-all clients, which let anyone on the network
 *   read or change our traffic (security audit 2026-11-23, finding 6).
 * - [request] adds the app-wide header and, once registered, this device's own auth
 *   token (`X-VG-Auth`), which the server requires for everything done on behalf of
 *   this user (findings 1/3/5).
 */
object ServerApi {

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    fun url(path: String): String = "https://${BuildConfig.TOKEN_SERVER_HOST}$path"

    /** Request builder for [path] (e.g. "/register") with the auth headers set. */
    fun request(path: String): Request.Builder {
        val b = Request.Builder()
            .url(url(path))
            .addHeader("Authorization", "Bearer ${BuildConfig.TOKEN_SERVER_SECRET}")
        val token = ServerDetectionManager.getAuthTokenOrEmpty()
        if (token.isNotEmpty()) b.addHeader("X-VG-Auth", token)
        return b
    }
}
