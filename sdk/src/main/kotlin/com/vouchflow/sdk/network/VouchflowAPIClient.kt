package dev.vouchflow.sdk.network

import android.content.Context
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import dev.vouchflow.sdk.VouchflowConfig
import dev.vouchflow.sdk.VouchflowError
import dev.vouchflow.sdk.internal.VouchflowLogger
import dev.vouchflow.sdk.network.models.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * HTTP client for all Vouchflow API endpoints.
 *
 * Pins API version to `2026-04-01`. The SDK is built against a specific version and the
 * server maintains backwards compatibility within that version per the API spec.
 *
 * All public methods are synchronous — call them from a coroutine with [kotlinx.coroutines.Dispatchers.IO].
 */
internal class VouchflowAPIClient(config: VouchflowConfig, context: Context) {

    private val baseUrl = config.environment.baseUrl
    private val apiKey = config.apiKey

    private val gson: Gson = GsonBuilder().create()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val client: OkHttpClient

    init {
        val pinningInterceptor = PinningInterceptor(config, PinningInterceptor.isDebugApp(context))
        val builder = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
        client = pinningInterceptor.configure(builder).build()
    }

    // ── Enrollment ────────────────────────────────────────────────────────────

    fun enroll(request: EnrollRequest): EnrollResponse =
        perform("POST", "/v1/enroll", request)

    // ── Verification ──────────────────────────────────────────────────────────

    fun initiateVerification(request: VerifyRequest): VerifyResponse =
        perform("POST", "/v1/verify", request)

    fun completeVerification(sessionId: String, request: CompleteVerificationRequest): CompleteVerificationResponse =
        perform("POST", "/v1/verify/$sessionId/complete", request)

    // ── Fallback ──────────────────────────────────────────────────────────────

    fun initiateFallback(sessionId: String, request: FallbackRequest): FallbackResponse =
        perform("POST", "/v1/verify/$sessionId/fallback", request)

    fun completeFallback(fallbackSessionId: String, request: FallbackCompleteRequest): FallbackCompleteResponse =
        // OTP submission reuses the complete endpoint, keyed by the fallback session ID.
        perform("POST", "/v1/verify/$fallbackSessionId/complete", request)

    // ── Core HTTP ─────────────────────────────────────────────────────────────

    private inline fun <reified T : Any> perform(
        method: String,
        path: String,
        body: Any
    ): T {
        val json = gson.toJson(body)
        val requestBody = json.toRequestBody(jsonMediaType)

        val request = Request.Builder()
            .url("$baseUrl$path")
            .method(method, requestBody)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .header("Vouchflow-API-Version", API_VERSION)
            .build()

        val response = try {
            client.newCall(request).execute()
        } catch (e: IOException) {
            // OkHttp throws IOException on network failure and SSLPeerUnverifiedException
            // (a subtype of IOException) when CertificatePinner rejects the chain.
            // OkHttp's actual pinning failure message is "Certificate pinning failure!" (with
            // exclamation mark, not "failed") — check for both forms plus our own interceptor msg.
            val msg = e.message ?: ""
            if (msg.contains("Certificate pinning failure") ||
                msg.contains("Certificate pinning failed") ||
                msg.contains("placeholder pins")) {
                throw VouchflowError.PinningFailure
            }
            throw VouchflowError.NetworkUnavailable
        }

        response.use { resp ->
            val responseBody = resp.body?.string() ?: ""

            if (resp.header("Vouchflow-Key-Deprecated") == "true") {
                VouchflowLogger.warn(
                    "[VouchflowSDK] Your Vouchflow API key is approaching its rotation deadline. " +
                    "Rotate your key in the developer dashboard before the deprecation window closes."
                )
            }

            when (resp.code) {
                in 200..299 -> {
                    return gson.fromJson(responseBody, T::class.java)
                }

                410 -> {
                    // Session expired — response body contains retry session data.
                    val errorResponse = runCatching {
                        gson.fromJson(responseBody, APIErrorResponse::class.java)
                    }.getOrNull()
                    val detail = errorResponse?.error
                    if (detail?.code == "session_expired" &&
                        detail.retrySessionId != null &&
                        detail.retryChallenge != null) {
                        throw VouchflowError.SessionExpiredInternal(
                            retrySessionId = detail.retrySessionId,
                            retryChallenge = detail.retryChallenge
                        )
                    }
                    throw VouchflowError.ServerError(
                        statusCode = 410,
                        code = detail?.code,
                        serverMessage = detail?.message
                    )
                }

                401 -> throw VouchflowError.InvalidApiKey

                else -> {
                    val detail = runCatching {
                        gson.fromJson(responseBody, APIErrorResponse::class.java)?.error
                    }.getOrNull()

                    if (detail?.code == "verification_impossible") {
                        throw VouchflowError.MinimumConfidenceUnmet
                    }

                    throw VouchflowError.ServerError(
                        statusCode = resp.code,
                        code = detail?.code,
                        serverMessage = detail?.message
                    )
                }
            }
        }
    }

    companion object {
        private const val API_VERSION = "2026-04-01"
    }
}
