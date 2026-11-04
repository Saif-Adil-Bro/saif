package com.saif.mosque.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.saif.mosque.data.SessionManager
import com.saif.mosque.data.model.AppUser
import com.saif.mosque.data.repository.AuthException
import com.saif.mosque.data.repository.AuthRepository
import com.saif.mosque.util.Msg
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface AuthUiState {
    data object Loading : AuthUiState
    data object Guest : AuthUiState
    data class Authed(val user: AppUser) : AuthUiState
}

class AuthViewModel(
    private val repo: AuthRepository,
    private val session: SessionManager
) : ViewModel() {

    val state: StateFlow<AuthUiState> = combine(repo.authState, session.userFlow) { fb, cached ->
        when {
            fb == null && cached == null -> AuthUiState.Guest
            fb != null && cached != null -> AuthUiState.Authed(cached)
            else -> AuthUiState.Loading
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AuthUiState.Loading)

    // replay = 1: while auth state is Loading the LoginScreen is not composed, so a
    // failure message emitted during login would be lost (silent failure). With
    // replay the message is re-delivered when the LoginScreen recomposes.
    private val _messages = MutableSharedFlow<Msg>(replay = 1)
    val messages = _messages.asSharedFlow()

    /** Last login lock: epoch millis until which login is disabled, or null. */
    var loginLockedUntilMs: Long? = null
        private set

    init {
        // If Firebase has a persisted session but the profile cache is empty, restore it.
        viewModelScope.launch {
            repo.authState.collect { fb ->
                if (fb != null && session.userFlow.first() == null) {
                    repo.refreshProfile()
                }
            }
        }
    }

    fun login(email: String, password: String) {
        viewModelScope.launch {
            val result = repo.login(email, password)
            result.onFailure { e ->
                android.util.Log.e("AuthViewModel", "login failed", e)
                if (e is AuthException) {
                    e.retryAfterMs?.let { loginLockedUntilMs = System.currentTimeMillis() + it }
                    // detail = raw Firebase error (e.g. "[ auth/user-not-found ]") for diagnosis
                    _messages.emit(Msg.Res(e.resId, e.message?.take(160)))
                } else {
                    _messages.emit(Msg.Text(e.message ?: "error"))
                }
            }
        }
    }

    fun logout() {
        viewModelScope.launch { repo.logout() }
    }
}
