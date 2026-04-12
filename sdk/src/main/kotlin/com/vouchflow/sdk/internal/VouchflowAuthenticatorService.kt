package com.vouchflow.sdk.internal

import android.accounts.AbstractAccountAuthenticator
import android.accounts.Account
import android.accounts.AccountAuthenticatorResponse
import android.accounts.NetworkErrorException
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.IBinder

/**
 * Stub AccountManager authenticator service for cross-reinstall device token persistence.
 *
 * AccountManager stores data at the OS account level, outside the app data sandbox, so it
 * survives app uninstalls on most devices. To use AccountManager for our account type
 * ("com.vouchflow.sdk"), we must declare an authenticator. This stub rejects all standard
 * account operations — the only capability we use is [android.accounts.AccountManager.getUserData]
 * and [android.accounts.AccountManager.setUserData] for key-value storage.
 *
 * Declared in the SDK's AndroidManifest.xml — merged automatically. No extra setup required
 * from the host app developer.
 */
internal class VouchflowAuthenticatorService : Service() {

    private lateinit var authenticator: VouchflowAccountAuthenticator

    override fun onCreate() {
        super.onCreate()
        authenticator = VouchflowAccountAuthenticator(this)
    }

    override fun onBind(intent: Intent?): IBinder = authenticator.iBinder
}

internal class VouchflowAccountAuthenticator(context: Context) : AbstractAccountAuthenticator(context) {

    override fun addAccount(
        response: AccountAuthenticatorResponse,
        accountType: String,
        authTokenType: String?,
        requiredFeatures: Array<out String>?,
        options: Bundle?
    ): Bundle? = null

    override fun confirmCredentials(
        response: AccountAuthenticatorResponse,
        account: Account,
        options: Bundle?
    ): Bundle? = null

    override fun editProperties(
        response: AccountAuthenticatorResponse,
        accountType: String
    ): Bundle? = null

    @Throws(NetworkErrorException::class)
    override fun getAuthToken(
        response: AccountAuthenticatorResponse,
        account: Account,
        authTokenType: String,
        options: Bundle?
    ): Bundle? = null

    override fun getAuthTokenLabel(authTokenType: String): String? = null

    override fun hasFeatures(
        response: AccountAuthenticatorResponse,
        account: Account,
        features: Array<out String>
    ): Bundle = Bundle()

    override fun updateCredentials(
        response: AccountAuthenticatorResponse,
        account: Account,
        authTokenType: String?,
        options: Bundle?
    ): Bundle? = null
}
