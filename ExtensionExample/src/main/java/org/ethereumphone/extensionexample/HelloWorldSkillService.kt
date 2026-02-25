package org.ethereumphone.extensionexample

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import org.json.JSONArray
import org.json.JSONObject

/**
 * Bound service that exposes an AndyClaw extension using the raw Binder
 * protocol expected by ApkExtensionExecutor.
 *
 * Wire protocol:
 *   Transaction 1 (GET_MANIFEST) → returns manifest JSON string
 *   Transaction 2 (EXECUTE)      → reads function name + params JSON, returns result JSON
 *
 * Interface descriptor must be "org.ethereumphone.andyclaw.IExtension".
 */
class HelloWorldSkillService : Service() {

    companion object {
        private const val DESCRIPTOR = "org.ethereumphone.andyclaw.IExtension"
        private const val TRANSACTION_GET_MANIFEST = 1
        private const val TRANSACTION_EXECUTE = 2
    }

    private val binder = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            return when (code) {
                TRANSACTION_GET_MANIFEST -> {
                    data.enforceInterface(DESCRIPTOR)
                    reply?.writeNoException()
                    reply?.writeString(buildManifestJson())
                    true
                }
                TRANSACTION_EXECUTE -> {
                    data.enforceInterface(DESCRIPTOR)
                    val function = data.readString() ?: ""
                    val paramsJson = data.readString() ?: "{}"
                    val result = executeFunction(function, paramsJson)
                    reply?.writeNoException()
                    reply?.writeString(result)
                    true
                }
                else -> super.onTransact(code, data, reply, flags)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private fun buildManifestJson(): String {
        val schema = JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject().apply {
                put("name", JSONObject().apply {
                    put("type", "string")
                    put("description", "The name to greet")
                })
            })
            put("required", JSONArray().apply { put("name") })
        }

        return JSONObject().apply {
            put("description", "A simple greeting skill that says hello.")
            put("tools", JSONArray().apply {
                put(JSONObject().apply {
                    put("name", "greet")
                    put("description", "Returns a friendly greeting for the given name.")
                    put("inputSchema", schema)
                })
            })
        }.toString()
    }

    private fun executeFunction(function: String, paramsJson: String): String {
        return when (function) {
            "greet" -> {
                val params = JSONObject(paramsJson)
                val name = params.optString("name", "World")
                JSONObject().apply {
                    put("greeting", "Hello, $name! Greetings from the ExtensionExample skill.")
                }.toString()
            }
            else -> {
                JSONObject().apply {
                    put("error", "Unknown function: $function")
                }.toString()
            }
        }
    }
}
