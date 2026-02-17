/*
 * Ported from Aurora Store
 * Copyright (C) 2021, Rahul Kumar Patel <whyorean@gmail.com>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.ethereumphone.andyclaw.skills.builtin.aurorastore

import android.util.Log
import com.aurora.gplayapi.data.models.AuthData
import com.aurora.gplayapi.data.serializers.PropertiesSerializer
import com.aurora.gplayapi.helpers.AuthHelper
import com.aurora.gplayapi.network.IHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual

/**
 * Response model for anonymous auth from Aurora Store dispenser.
 */
@Serializable
data class DispenserAuthResponse(
    val email: String,
    @SerialName("authToken")
    val auth: String
)

/**
 * Provides anonymous authentication with Google Play Store
 * using Aurora Store dispensers.
 */
class AuroraStoreAuthProvider(
    private val deviceInfoProvider: DeviceInfoProvider,
    private val httpClient: IHttpClient,
) {
    companion object {
        private const val TAG = "AuroraStoreAuth"
        private val DEFAULT_DISPENSERS = listOf(
            "https://auroraoss.com/api/auth",
        )
    }

    private val mutex = Mutex()
    private val json = Json {
        ignoreUnknownKeys = true
        serializersModule = SerializersModule {
            contextual(PropertiesSerializer)
        }
    }

    private var _authData: AuthData? = null

    val authData: AuthData? get() = _authData

    val isAuthenticated: Boolean
        get() = _authData != null && AuthHelper.isValid(_authData!!)

    /**
     * Authenticates anonymously using one of the available dispensers.
     */
    suspend fun authenticateAnonymously(): Result<AuthData> {
        return mutex.withLock {
            if (isAuthenticated) {
                return@withLock Result.success(_authData!!)
            }

            withContext(Dispatchers.IO) {
                var lastException: Exception? = null

                for (dispenserUrl in DEFAULT_DISPENSERS) {
                    try {
                        Log.i(TAG, "Attempting anonymous auth with dispenser: $dispenserUrl")

                        val playResponse = httpClient.postAuth(
                            dispenserUrl,
                            json.encodeToString(deviceInfoProvider.deviceProperties).toByteArray()
                        )

                        if (!playResponse.isSuccessful) {
                            Log.w(TAG, "Dispenser returned error code: ${playResponse.code}")
                            lastException = Exception("Auth failed with code: ${playResponse.code}")
                            continue
                        }

                        val authResponse = json.decodeFromString<DispenserAuthResponse>(
                            String(playResponse.responseBytes)
                        )

                        val authData = AuthHelper.build(
                            email = authResponse.email,
                            token = authResponse.auth,
                            tokenType = AuthHelper.Token.AUTH,
                            isAnonymous = true,
                            properties = deviceInfoProvider.deviceProperties,
                            locale = deviceInfoProvider.locale
                        )

                        _authData = authData
                        Log.i(TAG, "Anonymous authentication successful")
                        return@withContext Result.success(authData)

                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to authenticate with $dispenserUrl", e)
                        lastException = e
                    }
                }

                val errorMessage = lastException?.message ?: "All dispensers failed"
                return@withContext Result.failure(lastException ?: Exception(errorMessage))
            }
        }
    }

    /**
     * Forces re-authentication by clearing current auth data.
     */
    suspend fun refreshAuth(): Result<AuthData> {
        mutex.withLock {
            _authData = null
        }
        return authenticateAnonymously()
    }
}
