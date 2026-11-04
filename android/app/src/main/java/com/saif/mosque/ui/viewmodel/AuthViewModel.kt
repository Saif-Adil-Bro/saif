package com.saif.mosque.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.saif.mosque.data.SessionManager
import com.saif.mosque.data.model.AppUser
import com.saif.mosque.data.model.Role
import com.saif.mosque.data.repository.AuthException
import com.saif.mosque.data.repository.AuthRepository
import com.saif.mosque.util.Msg
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

sealed interface AuthUiState {
    data object Loading : AuthUiState
    data object Guest : AuthUiState
    data class Authed(val user: AppUser) : AuthUiState
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AuthViewModel(private val repo: AuthRepository, private val session: SessionManager) : ViewModel() {
    private val _state = MutableStateFlow<AuthUiState>(AuthUiState.Loading)
    val state: StateFlow<AuthUiState> = _state
    private val _messages = Channel<Msg>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()
    var loginLockedUntilMs: Long? by mutableStateOf(null)
        private set
    var loginLockedEmail: String? by mutableStateOf(null)
        private set
    var busy: Boolean by mutableStateOf(false)
        private set

    init {
        viewModelScope.launch {
            repo.authState.distinctUntilChangedBy { it?.uid }.collectLatest { fb ->
                if (fb == null) {
                    session.clear()
                    _state.value = AuthUiState.Guest
                } else {
                    _state.value = AuthUiState.Loading
                    val result = repo.refreshProfile()
                    if (result.isFailure) {
                        _state.value = AuthUiState.Guest
                        val e = result.exceptionOrNull()
                        _messages.trySend(if (e is AuthException) Msg.Res(e.resId)
                            else Msg.Res(com.saif.mosque.R.string.network_error))
                        repo.logout()
                    } else {
                        try {
                            repo.watchProfile(fb.uid).flatMapLatest { user ->
                                when {
                                    user == null || !user.active -> flowOf(null)
                                    user.role == Role.SUPERADMIN -> flowOf(user)
                                    user.mosqueId == null -> flowOf(null)
                                    else -> repo.watchMosqueActive(user.mosqueId).map { active -> if (active) user else null }
                                }
                            }.collect { user ->
                                if (user == null) repo.logout()
                                else { session.saveUser(user); _state.value = AuthUiState.Authed(user) }
                            }
                        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { repo.logout() }
                    }
                }
            }
        }
    }

    fun login(email: String, password: String) {
        if (busy) return
        busy = true
        viewModelScope.launch {
            try {
                val result = repo.login(email, password)
                result.onFailure { e ->
                    if (e is AuthException) {
                        e.retryAfterMs?.let {
                            loginLockedEmail = email.trim().lowercase()
                            loginLockedUntilMs = System.currentTimeMillis() + it
                        }
                        _messages.send(Msg.Res(e.resId, e.message?.take(160)))
                    } else _messages.send(Msg.Text(e.message ?: "error"))
                }
            } finally { busy = false }
        }
    }
    fun logout() { viewModelScope.launch { repo.logout() } }
}
