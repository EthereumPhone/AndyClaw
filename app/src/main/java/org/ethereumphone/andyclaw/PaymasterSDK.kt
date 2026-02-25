package org.ethereumphone.andyclaw

import android.annotation.SuppressLint
import android.content.Context
import java.lang.reflect.Method

class PaymasterSDK(private val context: Context) {

    private var paymasterProxyInstance: Any? = null
    private var getBalanceMethod: Method? = null
    private var queryUpdateMethod: Method? = null

    companion object {
        private const val PAYMASTER_SERVICE_NAME = "paymaster"
        private const val PAYMASTER_PROXY_CLASS_NAME = "android.os.PaymasterProxy"
    }

    @SuppressLint("WrongConstant")
    fun initialize(): Boolean {
        return try {
            paymasterProxyInstance = context.getSystemService(PAYMASTER_SERVICE_NAME)
                ?: return false

            val proxyClass = Class.forName(PAYMASTER_PROXY_CLASS_NAME)
            if (!proxyClass.isInstance(paymasterProxyInstance)) return false

            getBalanceMethod = proxyClass.getMethod("getBalance")
            queryUpdateMethod = proxyClass.getMethod("queryUpdate")
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun getCurrentBalance(): String? {
        return try {
            paymasterProxyInstance?.let { getBalanceMethod?.invoke(it) as? String }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun queryUpdate() {
        try {
            paymasterProxyInstance?.let { queryUpdateMethod?.invoke(it) }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun cleanup() {
        paymasterProxyInstance = null
    }
}
