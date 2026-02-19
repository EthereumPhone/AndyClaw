package org.ethereumphone.andyclaw.ui.heartbeatlogs

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.ethereumphone.andyclaw.NodeApp
import org.ethereumphone.andyclaw.heartbeat.HeartbeatLogEntry

class HeartbeatLogsViewModel(application: Application) : AndroidViewModel(application) {

    private val store = (application as NodeApp).heartbeatLogStore

    private val _logs = MutableStateFlow<List<HeartbeatLogEntry>>(emptyList())
    val logs: StateFlow<List<HeartbeatLogEntry>> = _logs.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _logs.value = store.getAll()
    }

    fun clearLogs() {
        store.clear()
        _logs.value = emptyList()
    }
}
