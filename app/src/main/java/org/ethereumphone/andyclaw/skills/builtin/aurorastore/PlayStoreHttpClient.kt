/*
 * Ported from Aurora Store
 * Copyright (C) 2021, Rahul Kumar Patel <whyorean@gmail.com>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.ethereumphone.andyclaw.skills.builtin.aurorastore

import android.util.Log
import com.aurora.gplayapi.data.models.PlayResponse
import com.aurora.gplayapi.network.IHttpClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Headers.Companion.toHeaders
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * HTTP client implementation for Play Store API communication.
 * Implements IHttpClient interface from gplayapi library.
 */
class PlayStoreHttpClient : IHttpClient {

    private val TAG = "PlayStoreHttpClient"

    private val POST = "POST"
    private val GET = "GET"

    private val _responseCode = MutableStateFlow(100)
    override val responseCode: StateFlow<Int>
        get() = _responseCode.asStateFlow()

    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(25, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .writeTimeout(25, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    @Throws(IOException::class)
    fun post(url: String, headers: Map<String, String>, requestBody: RequestBody): PlayResponse {
        val request = Request.Builder()
            .url(url.toHttpUrl())
            .headers(headers.toHeaders())
            .method(POST, requestBody)
            .build()
        return processRequest(request)
    }

    @Throws(IOException::class)
    override fun post(
        url: String,
        headers: Map<String, String>,
        params: Map<String, String>
    ): PlayResponse {
        val request = Request.Builder()
            .url(buildUrl(url, params))
            .headers(headers.toHeaders())
            .method(POST, "".toRequestBody(null))
            .build()
        return processRequest(request)
    }

    override fun postAuth(url: String, body: ByteArray): PlayResponse {
        val headers = mapOf("User-Agent" to "com.aurora.store-4.6.1-60")
        val requestBody = body.toRequestBody("application/json".toMediaType(), 0, body.size)
        val request = Request.Builder()
            .url(url.toHttpUrl())
            .headers(headers.toHeaders())
            .method(POST, requestBody)
            .build()
        return processRequest(request)
    }

    @Throws(IOException::class)
    override fun post(url: String, headers: Map<String, String>, body: ByteArray): PlayResponse {
        return post(url, headers, body.toRequestBody())
    }

    @Throws(IOException::class)
    override fun get(url: String, headers: Map<String, String>): PlayResponse {
        return get(url, headers, mapOf())
    }

    @Throws(IOException::class)
    override fun get(
        url: String,
        headers: Map<String, String>,
        params: Map<String, String>
    ): PlayResponse {
        val request = Request.Builder()
            .url(buildUrl(url, params))
            .headers(headers.toHeaders())
            .method(GET, null)
            .build()
        return processRequest(request)
    }

    override fun getAuth(url: String): PlayResponse {
        val headers = mapOf("User-Agent" to "com.aurora.store-4.6.1-60")
        val request = Request.Builder()
            .url(url.toHttpUrl())
            .headers(headers.toHeaders())
            .method(GET, null)
            .build()
        return processRequest(request)
    }

    @Throws(IOException::class)
    override fun get(
        url: String,
        headers: Map<String, String>,
        paramString: String
    ): PlayResponse {
        val request = Request.Builder()
            .url("$url$paramString".toHttpUrl())
            .headers(headers.toHeaders())
            .method(GET, null)
            .build()
        return processRequest(request)
    }

    private fun processRequest(request: Request): PlayResponse {
        _responseCode.value = 0
        val call = okHttpClient.newCall(request)
        return buildPlayResponse(call.execute())
    }

    private fun buildUrl(url: String, params: Map<String, String>): HttpUrl {
        val urlBuilder = url.toHttpUrl().newBuilder()
        params.forEach {
            urlBuilder.addQueryParameter(it.key, it.value)
        }
        return urlBuilder.build()
    }

    private fun buildPlayResponse(response: Response): PlayResponse {
        return PlayResponse(
            isSuccessful = response.isSuccessful,
            code = response.code,
            responseBytes = response.body?.bytes() ?: ByteArray(0),
            errorString = if (!response.isSuccessful) response.message else String()
        ).also {
            _responseCode.value = response.code
            Log.i(TAG, "OKHTTP [${response.code}] ${response.request.url}")
        }
    }
}
