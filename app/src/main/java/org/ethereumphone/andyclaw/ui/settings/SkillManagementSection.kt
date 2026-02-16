package org.ethereumphone.andyclaw.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.ethereumphone.andyclaw.skills.AndyClawSkill
import org.ethereumphone.andyclaw.skills.Tier
import org.ethereumphone.andyclaw.skills.tier.OsCapabilities

@Composable
fun SkillManagementSection(
    skills: List<AndyClawSkill>,
    enabledSkills: Set<String>,
    onToggleSkill: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tier = OsCapabilities.currentTier()

    Column(modifier = modifier) {
        Text(
            text = "Registered Skills",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        for (skill in skills) {
            SkillRow(
                skill = skill,
                tier = tier,
                enabled = skill.id in enabledSkills,
                onToggle = { onToggleSkill(skill.id, it) },
            )
        }
    }
}

@Composable
private fun SkillRow(
    skill: AndyClawSkill,
    tier: Tier,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = skill.name,
                style = MaterialTheme.typography.bodyLarge,
            )
            val baseToolCount = skill.baseManifest.tools.size
            val privToolCount = skill.privilegedManifest?.tools?.size ?: 0
            val toolText = buildString {
                append("$baseToolCount base tool${if (baseToolCount != 1) "s" else ""}")
                if (privToolCount > 0) {
                    append(", $privToolCount privileged")
                    if (tier != Tier.PRIVILEGED) append(" (locked)")
                }
            }
            Text(
                text = toolText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = enabled,
            onCheckedChange = onToggle,
        )
    }
}
