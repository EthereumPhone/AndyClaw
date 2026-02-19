package org.ethereumphone.andyclaw.onboarding

import android.app.Application
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.ethereumphone.andyclaw.BuildConfig
import org.ethereumphone.andyclaw.NodeApp
import org.ethereumphone.walletsdk.WalletSDK
import org.web3j.protocol.Web3j
import org.web3j.protocol.http.HttpService

/**
 * Standalone screen shown to existing ethOS users who have already completed
 * onboarding but haven't signed in with their wallet yet.
 */
@Composable
fun WalletSignScreen(
    onComplete: () -> Unit,
    viewModel: WalletSignViewModel = viewModel(),
) {
    val walletAddress by viewModel.walletAddress.collectAsState()
    val isSigning by viewModel.isSigning.collectAsState()
    val error by viewModel.error.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(error) {
        error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    // Once signed, persist and navigate
    LaunchedEffect(walletAddress) {
        if (walletAddress.isNotBlank()) {
            viewModel.persist()
            onComplete()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "Sign in with your wallet",
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "AndyClaw now requires a wallet signature to verify your identity for billing. Please sign once to continue.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(32.dp))

            Button(
                onClick = { viewModel.signWithWallet() },
                enabled = !isSigning,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (isSigning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text("Sign")
                }
            }
        }
    }
}

class WalletSignViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as NodeApp

    private val _walletAddress = MutableStateFlow("")
    val walletAddress: StateFlow<String> = _walletAddress.asStateFlow()

    private val _walletSignature = MutableStateFlow("")

    private val _isSigning = MutableStateFlow(false)
    val isSigning: StateFlow<Boolean> = _isSigning.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun signWithWallet() {
        if (_isSigning.value) return
        _isSigning.value = true
        _error.value = null

        viewModelScope.launch {
            try {
                val rpc = "https://eth-mainnet.g.alchemy.com/v2/${BuildConfig.ALCHEMY_API}"
                val sdk = WalletSDK(
                    context = app,
                    web3jInstance = Web3j.build(HttpService(rpc)),
                    bundlerRPCUrl = "https://api.pimlico.io/v2/1/rpc?apikey=${BuildConfig.BUNDLER_API}",
                )
                val address = withContext(Dispatchers.IO) { sdk.getAddress() }
                val sig = sdk.signMessage("Signing into AndyClaw", chainId = 1)
                _walletAddress.value = address
                _walletSignature.value = sig
            } catch (e: Exception) {
                Log.e("WalletSignVM", "Wallet signing failed", e)
                _error.value = "Wallet signing failed: ${e.message}"
            } finally {
                _isSigning.value = false
            }
        }
    }

    fun persist() {
        app.securePrefs.setWalletAuth(_walletAddress.value, _walletSignature.value)
    }

    fun clearError() {
        _error.value = null
    }
}
