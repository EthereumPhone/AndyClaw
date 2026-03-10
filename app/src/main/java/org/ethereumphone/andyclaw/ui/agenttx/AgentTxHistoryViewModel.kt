package org.ethereumphone.andyclaw.ui.agenttx

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.ethereumphone.andyclaw.NodeApp
import org.ethereumphone.andyclaw.agenttx.db.entity.AgentTxEntity

class AgentTxHistoryViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = (application as NodeApp).agentTxRepository

    val transactions: StateFlow<List<AgentTxEntity>> = repo.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun clearAll() {
        viewModelScope.launch { repo.clearAll() }
    }
}
