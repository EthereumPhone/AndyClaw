package org.ethereumphone.andyclaw

import android.app.Application
import org.ethereumphone.andyclaw.BuildConfig
import org.ethereumphone.andyclaw.llm.AnthropicClient
import org.ethereumphone.andyclaw.skills.NativeSkillRegistry
import org.ethereumphone.andyclaw.skills.builtin.AppsSkill
import org.ethereumphone.andyclaw.skills.builtin.CameraSkill
import org.ethereumphone.andyclaw.skills.builtin.ClipboardSkill
import org.ethereumphone.andyclaw.skills.builtin.ContactsSkill
import org.ethereumphone.andyclaw.skills.builtin.DeviceInfoSkill
import org.ethereumphone.andyclaw.skills.builtin.FileSystemSkill
import org.ethereumphone.andyclaw.skills.builtin.NotificationSkill
import org.ethereumphone.andyclaw.skills.builtin.SMSSkill
import org.ethereumphone.andyclaw.skills.builtin.SettingsSkill
import org.ethereumphone.andyclaw.skills.builtin.ProactiveAgentSkill
import org.ethereumphone.andyclaw.skills.builtin.ScreenSkill
import org.ethereumphone.andyclaw.skills.builtin.ShellSkill
import org.ethereumphone.andyclaw.skills.builtin.WalletSkill
import org.ethereumphone.andyclaw.skills.builtin.MessengerSkill
import org.ethereumphone.andyclaw.skills.tier.OsCapabilities
import org.ethereumphone.andyclaw.onboarding.UserStoryManager

class NodeApp : Application() {

    val runtime: NodeRuntime by lazy { NodeRuntime(this) }
    val securePrefs: SecurePrefs by lazy { SecurePrefs(this) }
    val userStoryManager: UserStoryManager by lazy { UserStoryManager(this) }

    var permissionRequester: PermissionRequester? = null

    val nativeSkillRegistry: NativeSkillRegistry by lazy {
        NativeSkillRegistry().apply {
            // Day 1 base skills
            register(DeviceInfoSkill(this@NodeApp))
            register(ClipboardSkill(this@NodeApp))
            register(ShellSkill(this@NodeApp))
            register(FileSystemSkill(this@NodeApp))
            // Day 2 tier-aware skills
            register(ContactsSkill(this@NodeApp))
            register(AppsSkill(this@NodeApp))
            register(NotificationSkill())
            register(SettingsSkill(this@NodeApp))
            register(CameraSkill(this@NodeApp))
            register(SMSSkill(this@NodeApp))
            // ethOS wallet skill
            register(WalletSkill(this@NodeApp))
            // XMTP messenger skill
            register(MessengerSkill(this@NodeApp))
            // Day 3 showcase skills
            register(ScreenSkill())
            register(ProactiveAgentSkill())
        }
    }

    val anthropicClient: AnthropicClient by lazy {
        AnthropicClient(apiKey = { BuildConfig.OPENROUTER_API_KEY })
    }

    override fun onCreate() {
        super.onCreate()
        OsCapabilities.init(this)
    }
}
