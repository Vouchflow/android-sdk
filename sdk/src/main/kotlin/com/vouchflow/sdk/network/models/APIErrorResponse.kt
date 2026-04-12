package com.vouchflow.sdk.network.models

import com.google.gson.annotations.SerializedName

internal data class APIErrorResponse(
    @SerializedName("error") val error: APIErrorDetail
)

internal data class APIErrorDetail(
    @SerializedName("code") val code: String?,
    @SerializedName("message") val message: String?,
    /** Present on 410 Session Expired — new session to continue with immediately. */
    @SerializedName("retry_session_id") val retrySessionId: String?,
    /** Present on 410 Session Expired — new challenge to sign. */
    @SerializedName("retry_challenge") val retryChallenge: String?
)
