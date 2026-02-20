package org.ethereumphone.andyclaw.ui.clawhub

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.ethereumphone.andyclaw.NodeApp
import org.ethereumphone.andyclaw.extensions.clawhub.ClawHubSearchResult
import org.ethereumphone.andyclaw.extensions.clawhub.ClawHubSkillSummary
import org.ethereumphone.andyclaw.extensions.clawhub.DownloadAssessResult
import org.ethereumphone.andyclaw.extensions.clawhub.InstalledClawHubSkill
import org.ethereumphone.andyclaw.extensions.clawhub.InstallResult
import org.ethereumphone.andyclaw.extensions.clawhub.ThreatAssessment
import org.ethereumphone.andyclaw.extensions.clawhub.ThreatLevel
import org.ethereumphone.andyclaw.extensions.clawhub.UpdateResult

class ClawHubViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as NodeApp
    private val manager = app.clawHubManager

    // ── Tab state ───────────────────────────────────────────────────────

    private val _selectedTab = MutableStateFlow(ClawHubTab.BROWSE)
    val selectedTab: StateFlow<ClawHubTab> = _selectedTab.asStateFlow()

    // ── Search state ────────────────────────────────────────────────────

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _searchResults = MutableStateFlow<List<ClawHubSearchResult>>(emptyList())
    val searchResults: StateFlow<List<ClawHubSearchResult>> = _searchResults.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    // ── Browse state ────────────────────────────────────────────────────

    private val _browseSkills = MutableStateFlow<List<ClawHubSkillSummary>>(emptyList())
    val browseSkills: StateFlow<List<ClawHubSkillSummary>> = _browseSkills.asStateFlow()

    private val _isBrowsing = MutableStateFlow(false)
    val isBrowsing: StateFlow<Boolean> = _isBrowsing.asStateFlow()

    private var browseCursor: String? = null
    private var hasMoreBrowse = true

    // ── Installed state ─────────────────────────────────────────────────

    private val _installedSkills = MutableStateFlow<List<InstalledClawHubSkill>>(emptyList())
    val installedSkills: StateFlow<List<InstalledClawHubSkill>> = _installedSkills.asStateFlow()

    // ── Operation state (install/uninstall/update) ──────────────────────

    /** Slug currently being operated on (install, uninstall, or update). */
    private val _operatingSlug = MutableStateFlow<String?>(null)
    val operatingSlug: StateFlow<String?> = _operatingSlug.asStateFlow()

    private val _snackbarMessage = MutableStateFlow<String?>(null)
    val snackbarMessage: StateFlow<String?> = _snackbarMessage.asStateFlow()

    // ── Pending install confirmation ─────────────────────────────────────

    private val _pendingInstall = MutableStateFlow<PendingSkillInstall?>(null)
    val pendingInstall: StateFlow<PendingSkillInstall?> = _pendingInstall.asStateFlow()

    private var searchJob: Job? = null

    init {
        loadBrowse()
        refreshInstalled()
    }

    // ── Tab ─────────────────────────────────────────────────────────────

    fun selectTab(tab: ClawHubTab) {
        _selectedTab.value = tab
        if (tab == ClawHubTab.INSTALLED) refreshInstalled()
    }

    // ── Search ──────────────────────────────────────────────────────────

    fun onSearchQueryChange(query: String) {
        _searchQuery.value = query
        searchJob?.cancel()
        if (query.isBlank()) {
            _searchResults.value = emptyList()
            return
        }
        // Debounce 400ms before firing the search
        searchJob = viewModelScope.launch {
            delay(400)
            performSearch(query)
        }
    }

    fun submitSearch() {
        val query = _searchQuery.value
        if (query.isBlank()) return
        searchJob?.cancel()
        searchJob = viewModelScope.launch { performSearch(query) }
    }

    private suspend fun performSearch(query: String) {
        _isSearching.value = true
        try {
            _searchResults.value = manager.search(query, limit = 30)
        } finally {
            _isSearching.value = false
        }
    }

    // ── Browse ──────────────────────────────────────────────────────────

    fun loadBrowse() {
        if (_isBrowsing.value) return
        viewModelScope.launch {
            _isBrowsing.value = true
            try {
                val response = manager.browse(cursor = null)
                _browseSkills.value = response.items
                browseCursor = response.nextCursor
                hasMoreBrowse = response.nextCursor != null
            } finally {
                _isBrowsing.value = false
            }
        }
    }

    fun loadMoreBrowse() {
        if (_isBrowsing.value || !hasMoreBrowse) return
        viewModelScope.launch {
            _isBrowsing.value = true
            try {
                val response = manager.browse(cursor = browseCursor)
                _browseSkills.value = _browseSkills.value + response.items
                browseCursor = response.nextCursor
                hasMoreBrowse = response.nextCursor != null
            } finally {
                _isBrowsing.value = false
            }
        }
    }

    // ── Install (two-phase with threat assessment) ─────────────────────

    fun installSkill(slug: String) {
        if (_operatingSlug.value != null) return
        viewModelScope.launch {
            _operatingSlug.value = slug
            try {
                when (val result = manager.downloadAndAssess(slug)) {
                    is DownloadAssessResult.Ready -> {
                        if (result.assessment.level >= ThreatLevel.MEDIUM) {
                            _pendingInstall.value = PendingSkillInstall(
                                slug = result.slug,
                                version = result.version,
                                assessment = result.assessment,
                            )
                        } else {
                            finaliseInstall(result.slug, result.version)
                        }
                    }
                    is DownloadAssessResult.AlreadyInstalled ->
                        showSnackbar("'${result.slug}' is already installed")
                    is DownloadAssessResult.Failed ->
                        showSnackbar("Failed: ${result.reason}")
                }
            } finally {
                if (_pendingInstall.value == null) {
                    _operatingSlug.value = null
                }
            }
        }
    }

    fun confirmPendingInstall() {
        val pending = _pendingInstall.value ?: return
        viewModelScope.launch {
            try {
                finaliseInstall(pending.slug, pending.version)
            } finally {
                _pendingInstall.value = null
                _operatingSlug.value = null
            }
        }
    }

    fun cancelPendingInstall() {
        val pending = _pendingInstall.value ?: return
        viewModelScope.launch {
            try {
                manager.cancelPendingInstall(pending.slug)
                showSnackbar("Installation of '${pending.slug}' cancelled")
            } finally {
                _pendingInstall.value = null
                _operatingSlug.value = null
            }
        }
    }

    private suspend fun finaliseInstall(slug: String, version: String?) {
        when (val result = manager.confirmInstall(slug, version)) {
            is InstallResult.Success ->
                showSnackbar("Installed '${result.slug}' v${result.version ?: "latest"}")
            is InstallResult.AlreadyInstalled ->
                showSnackbar("'${result.slug}' is already installed")
            is InstallResult.Failed ->
                showSnackbar("Failed: ${result.reason}")
        }
        refreshInstalled()
    }

    // ── Uninstall ───────────────────────────────────────────────────────

    fun uninstallSkill(slug: String) {
        if (_operatingSlug.value != null) return
        viewModelScope.launch {
            _operatingSlug.value = slug
            try {
                val success = manager.uninstall(slug)
                if (success) {
                    showSnackbar("Uninstalled '$slug'")
                } else {
                    showSnackbar("Failed to uninstall '$slug'")
                }
                refreshInstalled()
            } finally {
                _operatingSlug.value = null
            }
        }
    }

    // ── Update ──────────────────────────────────────────────────────────

    fun updateSkill(slug: String) {
        if (_operatingSlug.value != null) return
        viewModelScope.launch {
            _operatingSlug.value = slug
            try {
                when (val result = manager.update(slug)) {
                    is UpdateResult.Updated ->
                        showSnackbar("Updated '${result.slug}' to v${result.toVersion}")
                    is UpdateResult.AlreadyUpToDate ->
                        showSnackbar("'${result.slug}' is already up to date")
                    is UpdateResult.NotInstalled ->
                        showSnackbar("'${result.slug}' is not installed")
                    is UpdateResult.Failed ->
                        showSnackbar("Update failed: ${result.reason}")
                }
                refreshInstalled()
            } finally {
                _operatingSlug.value = null
            }
        }
    }

    // ── Snackbar ────────────────────────────────────────────────────────

    fun dismissSnackbar() {
        _snackbarMessage.value = null
    }

    private fun showSnackbar(message: String) {
        _snackbarMessage.value = message
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    fun isSkillInstalled(slug: String): Boolean = manager.isInstalled(slug)

    private fun refreshInstalled() {
        _installedSkills.value = manager.listInstalled()
    }
}

enum class ClawHubTab { BROWSE, INSTALLED }

/**
 * Holds state for a skill that has been downloaded and assessed but is
 * awaiting user confirmation before being registered.
 */
data class PendingSkillInstall(
    val slug: String,
    val version: String?,
    val assessment: ThreatAssessment,
)
