package com.hermex.v3.feature.onboarding

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hermex.core.data.auth.KeychainStore
import com.hermex.core.network.DashboardApiClient
import com.hermex.core.network.DebugLog
import com.hermex.core.network.NetworkResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class DashboardSetupUiState(
    val dashboardUrl: String = "http://100.80.204.66:9119",
    val dashboardPassword: String = "",
    val isLoading: Boolean = false,
    val connectionOk: Boolean = false,
    val statusMessage: String? = null,
    val error: String? = null,
)

class DashboardSetupViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(DashboardSetupUiState())
    val uiState: StateFlow<DashboardSetupUiState> = _uiState.asStateFlow()

    fun updateDashboardUrl(url: String) {
        _uiState.value = _uiState.value.copy(dashboardUrl = url, error = null)
    }

    fun updateDashboardPassword(password: String) {
        _uiState.value = _uiState.value.copy(dashboardPassword = password, error = null)
    }

    fun testConnection() {
        val state = _uiState.value
        val url = state.dashboardUrl.trim()
        val password = state.dashboardPassword

        if (url.isBlank()) {
            _uiState.value = state.copy(error = "Dashboard URL is required")
            return
        }
        if (password.isBlank()) {
            _uiState.value = state.copy(error = "Password is required")
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            DebugLog.log("INFO", "DashboardSetup", "testing connection → $url")
            Log.d("Hermex", "DashboardSetupViewModel: testing $url")

            try {
                // Step 1: verify server reachable
                when (val statusResult = DashboardApiClient.status(url)) {
                    is NetworkResult.Success -> {
                        val s = statusResult.data
                        DebugLog.log("INFO", "DashboardSetup", "status OK — v${s.version}")
                        Log.d("Hermex", "DashboardSetupViewModel: status OK v${s.version}")

                        // Step 2: authenticate
                        DashboardApiClient.setDashboardUrl(url)
                        DashboardApiClient.setPassword(password)
                        DashboardApiClient.setUsername("jeff")

                        when (val loginResult = DashboardApiClient.login("jeff", password)) {
                            is NetworkResult.Success -> {
                                DebugLog.log("INFO", "DashboardSetup", "login OK")
                                KeychainStore.saveDashboardCredentials(
                                    getApplication(), url, password, "jeff"
                                )
                                _uiState.value = _uiState.value.copy(
                                    isLoading = false,
                                    connectionOk = true,
                                    statusMessage = "✓ Connected — Hermes v${s.version ?: "?"}${if (s.gateway_running) ", gateway running" else ""}",
                                    error = null,
                                )
                            }
                            is NetworkResult.HttpError -> {
                                _uiState.value = _uiState.value.copy(
                                    isLoading = false,
                                    error = "Login failed (${loginResult.code}) — check password",
                                )
                            }
                            is NetworkResult.Error -> {
                                _uiState.value = _uiState.value.copy(
                                    isLoading = false,
                                    error = loginResult.exception.message ?: "Login failed",
                                )
                            }
                        }
                    }
                    is NetworkResult.HttpError -> {
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            error = "Server unreachable (${statusResult.code}) — check URL",
                        )
                    }
                    is NetworkResult.Error -> {
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            error = statusResult.exception.message ?: "Connection failed — check URL and network",
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e("Hermex", "DashboardSetupViewModel: testConnection crashed", e)
                DebugLog.log("ERROR", "DashboardSetup", "crash: ${e.message}")
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "App error: ${e.message}",
                )
            }
        }
    }
}
