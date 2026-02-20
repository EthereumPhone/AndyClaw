package org.ethereumphone.andyclaw.extensions.clawhub

import kotlinx.serialization.Serializable

/**
 * Data models for the ClawHub public skill registry API (v1).
 *
 * These mirror the API response schemas from clawhub.ai's v1 endpoints.
 * The registry stores versioned SKILL.md bundles and exposes them via
 * REST search, browse, resolve, and download endpoints.
 */

// ── Moderation ──────────────────────────────────────────────────────

@Serializable
data class ClawHubModeration(
    val isSuspicious: Boolean = false,
    val isMalwareBlocked: Boolean = false,
)

// ── Search ──────────────────────────────────────────────────────────

@Serializable
data class ClawHubSearchResult(
    val slug: String? = null,
    val displayName: String? = null,
    val summary: String? = null,
    val version: String? = null,
    val score: Double = 0.0,
    val updatedAt: Long? = null,
    val moderation: ClawHubModeration? = null,
)

@Serializable
data class ClawHubSearchResponse(
    val results: List<ClawHubSearchResult> = emptyList(),
)

// ── Skill listing / detail ──────────────────────────────────────────

@Serializable
data class ClawHubSkillVersion(
    val version: String,
    val createdAt: Long = 0L,
    val changelog: String = "",
    val changelogSource: String? = null,
)

@Serializable
data class ClawHubSkillStats(
    val downloads: Long = 0L,
    val stars: Long = 0L,
)

@Serializable
data class ClawHubSkillSummary(
    val slug: String,
    val displayName: String,
    val summary: String? = null,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val latestVersion: ClawHubSkillVersion? = null,
    val moderation: ClawHubModeration? = null,
)

@Serializable
data class ClawHubSkillOwner(
    val handle: String? = null,
    val displayName: String? = null,
    val image: String? = null,
)

@Serializable
data class ClawHubSkillDetail(
    val skill: ClawHubSkillSummary? = null,
    val latestVersion: ClawHubSkillVersion? = null,
    val owner: ClawHubSkillOwner? = null,
    val moderation: ClawHubModeration? = null,
)

@Serializable
data class ClawHubSkillListResponse(
    val items: List<ClawHubSkillSummary> = emptyList(),
    val nextCursor: String? = null,
)

@Serializable
data class ClawHubVersionListResponse(
    val items: List<ClawHubSkillVersion> = emptyList(),
    val nextCursor: String? = null,
)

// ── Resolve (hash-based version matching) ───────────────────────────

@Serializable
data class ClawHubResolveMatch(
    val version: String,
)

@Serializable
data class ClawHubResolveResponse(
    val match: ClawHubResolveMatch? = null,
    val latestVersion: ClawHubResolveMatch? = null,
)

// ── Lockfile (.clawhub/lock.json) ───────────────────────────────────

@Serializable
data class ClawHubLockEntry(
    val version: String? = null,
    val installedAt: Long = 0L,
)

@Serializable
data class ClawHubLockData(
    val version: Int = 1,
    val skills: MutableMap<String, ClawHubLockEntry> = mutableMapOf(),
)

// ── Installed skill info (local tracking) ───────────────────────────

/**
 * Represents a ClawHub skill that has been installed locally.
 * Tracks the slug, installed version, and local directory path.
 */
data class InstalledClawHubSkill(
    val slug: String,
    val displayName: String,
    val version: String?,
    val installedAt: Long,
    val localDir: String,
)
