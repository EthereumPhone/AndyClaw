package org.ethereumphone.andyclaw.ui.clawhub

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import org.ethereumphone.andyclaw.extensions.clawhub.ClawHubSearchResult
import org.ethereumphone.andyclaw.extensions.clawhub.ClawHubSkillSummary
import org.ethereumphone.andyclaw.extensions.clawhub.InstalledClawHubSkill

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClawHubScreen(
    onNavigateBack: () -> Unit,
    viewModel: ClawHubViewModel = viewModel(),
) {
    val selectedTab by viewModel.selectedTab.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val searchResults by viewModel.searchResults.collectAsState()
    val isSearching by viewModel.isSearching.collectAsState()
    val browseSkills by viewModel.browseSkills.collectAsState()
    val isBrowsing by viewModel.isBrowsing.collectAsState()
    val installedSkills by viewModel.installedSkills.collectAsState()
    val operatingSlug by viewModel.operatingSlug.collectAsState()
    val snackbarMessage by viewModel.snackbarMessage.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }

    // Show snackbar when message arrives
    LaunchedEffect(snackbarMessage) {
        snackbarMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissSnackbar()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ClawHub") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // Tab row
            TabRow(selectedTabIndex = selectedTab.ordinal) {
                Tab(
                    selected = selectedTab == ClawHubTab.BROWSE,
                    onClick = { viewModel.selectTab(ClawHubTab.BROWSE) },
                    text = { Text("Browse") },
                )
                Tab(
                    selected = selectedTab == ClawHubTab.INSTALLED,
                    onClick = { viewModel.selectTab(ClawHubTab.INSTALLED) },
                    text = {
                        Text(
                            if (installedSkills.isEmpty()) "Installed"
                            else "Installed (${installedSkills.size})"
                        )
                    },
                )
            }

            when (selectedTab) {
                ClawHubTab.BROWSE -> BrowseTab(
                    searchQuery = searchQuery,
                    onSearchQueryChange = viewModel::onSearchQueryChange,
                    onSubmitSearch = viewModel::submitSearch,
                    isSearching = isSearching,
                    searchResults = searchResults,
                    browseSkills = browseSkills,
                    isBrowsing = isBrowsing,
                    onLoadMore = viewModel::loadMoreBrowse,
                    operatingSlug = operatingSlug,
                    isInstalled = viewModel::isSkillInstalled,
                    onInstall = viewModel::installSkill,
                    onUninstall = viewModel::uninstallSkill,
                )

                ClawHubTab.INSTALLED -> InstalledTab(
                    skills = installedSkills,
                    operatingSlug = operatingSlug,
                    onUninstall = viewModel::uninstallSkill,
                    onUpdate = viewModel::updateSkill,
                )
            }
        }
    }
}

// ── Browse tab ──────────────────────────────────────────────────────

@Composable
private fun BrowseTab(
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onSubmitSearch: () -> Unit,
    isSearching: Boolean,
    searchResults: List<ClawHubSearchResult>,
    browseSkills: List<ClawHubSkillSummary>,
    isBrowsing: Boolean,
    onLoadMore: () -> Unit,
    operatingSlug: String?,
    isInstalled: (String) -> Boolean,
    onInstall: (String) -> Unit,
    onUninstall: (String) -> Unit,
) {
    val listState = rememberLazyListState()
    val showSearch = searchQuery.isNotBlank() || searchResults.isNotEmpty()

    // Trigger load more when user scrolls near the bottom
    val shouldLoadMore by remember {
        derivedStateOf {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val total = listState.layoutInfo.totalItemsCount
            lastVisible >= total - 3 && !isBrowsing
        }
    }
    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore && !showSearch) onLoadMore()
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Search bar
        item(key = "search") {
            SearchBar(
                query = searchQuery,
                onQueryChange = onSearchQueryChange,
                onSubmit = onSubmitSearch,
                isSearching = isSearching,
            )
            Spacer(Modifier.height(8.dp))
        }

        if (showSearch) {
            // Search results
            if (searchResults.isEmpty() && !isSearching) {
                item(key = "no-results") {
                    EmptyState(
                        title = "No results",
                        subtitle = "Try a different search query",
                    )
                }
            } else {
                items(
                    items = searchResults,
                    key = { it.slug ?: it.hashCode().toString() },
                ) { result ->
                    val slug = result.slug ?: return@items
                    SearchResultCard(
                        result = result,
                        installed = isInstalled(slug),
                        isOperating = operatingSlug == slug,
                        onInstall = { onInstall(slug) },
                        onUninstall = { onUninstall(slug) },
                    )
                }
            }
        } else {
            // Browse listing
            if (browseSkills.isEmpty() && !isBrowsing) {
                item(key = "empty-browse") {
                    EmptyState(
                        title = "No skills available",
                        subtitle = "Check back later or try searching",
                    )
                }
            } else {
                items(
                    items = browseSkills,
                    key = { it.slug },
                ) { skill ->
                    BrowseSkillCard(
                        skill = skill,
                        installed = isInstalled(skill.slug),
                        isOperating = operatingSlug == skill.slug,
                        onInstall = { onInstall(skill.slug) },
                        onUninstall = { onUninstall(skill.slug) },
                    )
                }
            }

            // Loading indicator at the bottom
            if (isBrowsing) {
                item(key = "loading") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    }
                }
            }
        }
    }
}

// ── Installed tab ───────────────────────────────────────────────────

@Composable
private fun InstalledTab(
    skills: List<InstalledClawHubSkill>,
    operatingSlug: String?,
    onUninstall: (String) -> Unit,
    onUpdate: (String) -> Unit,
) {
    if (skills.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            contentAlignment = Alignment.Center,
        ) {
            EmptyState(
                title = "No ClawHub skills installed",
                subtitle = "Browse the registry and install skills to extend your agent",
            )
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(
                items = skills,
                key = { it.slug },
            ) { skill ->
                InstalledSkillCard(
                    skill = skill,
                    isOperating = operatingSlug == skill.slug,
                    onUninstall = { onUninstall(skill.slug) },
                    onUpdate = { onUpdate(skill.slug) },
                )
            }
        }
    }
}

// ── Search bar ──────────────────────────────────────────────────────

@Composable
private fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    isSearching: Boolean,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text("Search skills on ClawHub…") },
        leadingIcon = {
            if (isSearching) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                Icon(Icons.Default.Search, contentDescription = null)
            }
        },
        trailingIcon = {
            AnimatedVisibility(visible = query.isNotEmpty(), enter = fadeIn(), exit = fadeOut()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Default.Close, contentDescription = "Clear")
                }
            }
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
    )
}

// ── Cards ───────────────────────────────────────────────────────────

@Composable
private fun SearchResultCard(
    result: ClawHubSearchResult,
    installed: Boolean,
    isOperating: Boolean,
    onInstall: () -> Unit,
    onUninstall: () -> Unit,
) {
    SkillCardShell(
        name = result.displayName ?: result.slug ?: "Unknown",
        slug = result.slug,
        description = result.summary,
        version = result.version,
        installed = installed,
        isOperating = isOperating,
        onInstall = onInstall,
        onUninstall = onUninstall,
    )
}

@Composable
private fun BrowseSkillCard(
    skill: ClawHubSkillSummary,
    installed: Boolean,
    isOperating: Boolean,
    onInstall: () -> Unit,
    onUninstall: () -> Unit,
) {
    SkillCardShell(
        name = skill.displayName,
        slug = skill.slug,
        description = skill.summary,
        version = skill.latestVersion?.version,
        installed = installed,
        isOperating = isOperating,
        onInstall = onInstall,
        onUninstall = onUninstall,
    )
}

@Composable
private fun InstalledSkillCard(
    skill: InstalledClawHubSkill,
    isOperating: Boolean,
    onUninstall: () -> Unit,
    onUpdate: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = skill.displayName,
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = buildString {
                    append(skill.slug)
                    if (skill.version != null) append(" · v${skill.version}")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
            )
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isOperating) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    OutlinedButton(onClick = onUpdate) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("Update")
                    }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = onUninstall) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("Uninstall", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

/**
 * Shared card layout for browse and search result items.
 */
@Composable
private fun SkillCardShell(
    name: String,
    slug: String?,
    description: String?,
    version: String?,
    installed: Boolean,
    isOperating: Boolean,
    onInstall: () -> Unit,
    onUninstall: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (slug != null) {
                        Text(
                            text = buildString {
                                append(slug)
                                if (version != null) append(" · v$version")
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Spacer(Modifier.width(12.dp))

                if (isOperating) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                } else if (installed) {
                    OutlinedButton(onClick = onUninstall) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("Uninstall", color = MaterialTheme.colorScheme.error)
                    }
                } else {
                    FilledTonalButton(onClick = onInstall) {
                        Text("Install")
                    }
                }
            }

            if (!description.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// ── Empty state ─────────────────────────────────────────────────────

@Composable
private fun EmptyState(title: String, subtitle: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        )
    }
}
