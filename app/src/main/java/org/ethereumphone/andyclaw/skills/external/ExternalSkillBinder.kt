package org.ethereumphone.andyclaw.skills.external

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.ethereumphone.andyclaw.skills.AndyClawSkill
import org.ethereumphone.andyclaw.skills.SkillManifest
import org.ethereumphone.andyclaw.skills.SkillResult
import org.ethereumphone.andyclaw.skills.Tier
import org.ethereumphone.andyclaw.skills.ToolDefinition
import kotlin.coroutines.resume

class ExternalSkillBinder(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }
    private val connections = mutableMapOf<String, ServiceConnection>()

    suspend fun bind(info: ExternalSkillInfo): AndyClawSkill? {
        return try {
            val binder = withTimeout(5000) {
                suspendCancellableCoroutine<IBinder?> { cont ->
                    val intent = Intent().apply {
                        component = ComponentName(info.packageName, info.serviceName)
                    }
                    val connection = object : ServiceConnection {
                        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                            cont.resume(service)
                        }
                        override fun onServiceDisconnected(name: ComponentName?) {}
                    }
                    connections[info.skillId] = connection
                    val bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
                    if (!bound) cont.resume(null)
                }
            } ?: return null

            // Create adapter from AIDL binder
            ExternalSkillAdapter(info.skillId, binder, json)
        } catch (_: Exception) {
            null
        }
    }

    fun unbind(skillId: String) {
        connections.remove(skillId)?.let { context.unbindService(it) }
    }

    fun unbindAll() {
        connections.keys.toList().forEach { unbind(it) }
    }
}

private class ExternalSkillAdapter(
    override val id: String,
    private val binder: IBinder,
    private val json: Json,
) : AndyClawSkill {

    override val name: String = id
    override val baseManifest: SkillManifest = SkillManifest(
        description = "External skill: $id",
        tools = emptyList(), // Populated from getManifestJson() call
    )
    override val privilegedManifest: SkillManifest? = null

    override suspend fun execute(tool: String, params: JsonObject, tier: Tier): SkillResult {
        // In a full implementation, this would call through AIDL
        return SkillResult.Error("External skill execution not yet implemented for $id")
    }
}
