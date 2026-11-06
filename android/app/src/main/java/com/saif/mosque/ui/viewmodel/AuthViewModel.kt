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
import com.saif.mosque.util.OfflineSessionPolicy
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

sealed interface AuthUiState {
    data object Loading : AuthUiState
    data object Guest : AuthUiState
    data class Authed(val user: AppUser, val offline: Boolean = false) : AuthUiState
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AuthViewModel(
    private val repo: AuthRepository,
    private val session: SessionManager,
    private val connectivity: kotlinx.coroutines.flow.Flow<Boolean>
) : ViewModel() {
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
            combine(repo.authState.distinctUntilChangedBy { it?.uid }, connectivity) { fb, online -> fb to online }
                .collectLatest { (fb, online) ->
                    if (fb == null) {
                        session.clear()
                        _state.value = AuthUiState.Guest
                        return@collectLatest
                    }
                    if (!online) {
                        openCachedSession(fb.uid)
                        return@collectLatest
                    }
                    // A temporary Firebase outage is not the same as account revocation.
                    // Keep a matching cached session read-only and retry validation.
                    var retryDelay = 2_000L
                    var attempts = 0
                    while (true) {
                        val result = repo.refreshProfile()
                        if (result.isSuccess) {
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
                            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                                if (!isTemporaryFailure(e)) { repo.logout(); return@collectLatest }
                                openCachedSession(fb.uid)
                                delay(retryDelay)
                                retryDelay = (retryDelay * 2).coerceAtMost(8_000L)
                                continue
                            }
                            return@collectLatest
                        }
                        val error = result.exceptionOrNull()
                        if (error != null && isTemporaryFailure(error)) {
                            attempts++
                            // When the device has mobile data/Wi-Fi connected, give Firestore
                            // 2 quick retry attempts (1s, 2s) to establish its gRPC channel before
                            // declaring offline mode.
                            if (attempts <= 2) {
                                delay(1_000L * attempts)
                                continue
                            }
                            openCachedSession(fb.uid)
                            delay(retryDelay)
                            retryDelay = (retryDelay * 2).coerceAtMost(8_000L)
                        } else {
                            _state.value = AuthUiState.Guest
                            _messages.trySend(if (error is AuthException) Msg.Res(error.resId)
                                else Msg.Res(com.saif.mosque.R.string.network_error))
                            repo.logout()
                            return@collectLatest
                        }
                    }
                }
        }
    }

    private suspend fun openCachedSession(uid: String) {
        val cached = session.userFlow.first()
        if (OfflineSessionPolicy.mayOpen(uid, cached)) {
            _state.value = AuthUiState.Authed(cached!!, offline = true)
        } else {
            // No verified cache: do not silently grant access, and keep the Firebase
            // session so it can be validated when connectivity returns.
            _state.value = AuthUiState.Guest
            _messages.trySend(Msg.Res(com.saif.mosque.R.string.offline_login_required))
        }
    }

    private fun isTemporaryFailure(error: Throwable): Boolean =
        error is FirebaseNetworkException ||
            (error is FirebaseFirestoreException && error.code in setOf(
                FirebaseFirestoreException.Code.UNAVAILABLE,
                FirebaseFirestoreException.Code.DEADLINE_EXCEEDED))

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
