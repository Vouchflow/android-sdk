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
    private val hostname = config.environment.hostname
    // Snapshot the pins so PinningFailure can echo them back — Config is immutable,
    // so holding the strings is harmless and saves dragging the whole config around.
    private val configuredPins = listOf(config.leafCertificatePin, config.intermediateCertificatePin)

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

    // ── Sign ──────────────────────────────────────────────────────────────────

    fun initiateSign(request: SignInitiateRequest): SignInitiateResponse =
        perform("POST", "/v1/sign", request)

    fun completeSign(sessionId: String, request: SignCompleteRequest): SignCompleteResponse =
        perform("POST", "/v1/sign/$sessionId/complete", request)

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
                throw VouchflowError.PinningFailure(
                    hostname = hostname,
                    configuredPins = configuredPins,
                    servedSpkiSha256 = extractServedPinsFromOkHttpMessage(msg),
                    pinningCause = e,
                )
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

        // OkHttp's SSLPeerUnverifiedException message lays out the chain like:
        //   Certificate pinning failure!
        //     Peer certificate chain:
        //       sha256/<served_leaf>: CN=api.vouchflow.dev
        //       sha256/<served_inter>: CN=YE1, O=Let's Encrypt, C=US
        //     Pinned certificates for api.vouchflow.dev:
        //       sha256/<configured_1>
        //       sha256/<configured_2>
        // We want the served set (the first block). Split on the "Pinned certificates"
        // marker, scan the prefix half for sha256/ tokens, strip the prefix so the
        // values returned to the caller are the same format they configured. Returns
        // an empty list if the message isn't OkHttp's (e.g. our placeholder-pins path).
        internal fun extractServedPinsFromOkHttpMessage(msg: String): List<String> {
            val chainSection = msg.substringBefore("Pinned certificates for", missingDelimiterValue = "")
            if (chainSection.isEmpty()) return emptyList()
            return Regex("""sha256/([A-Za-z0-9+/=]+)""")
                .findAll(chainSection)
                .map { it.groupValues[1] }
                .toList()
        }
    }
}
