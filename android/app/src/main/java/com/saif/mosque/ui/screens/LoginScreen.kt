package com.saif.mosque.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.saif.mosque.R
import com.saif.mosque.ui.components.LabeledField
import com.saif.mosque.ui.components.PasswordField
import com.saif.mosque.ui.viewmodel.AuthViewModel
import com.saif.mosque.util.Config
import com.saif.mosque.util.Format
import com.saif.mosque.util.Msg
import com.saif.mosque.util.Validators
import kotlinx.coroutines.delay

@Composable
fun LoginScreen(authVm: AuthViewModel, isWideScreen: Boolean) {
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var emailError by remember { mutableStateOf<String?>(null) }
    var passwordError by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var lockUntil by remember { mutableStateOf<Long?>(null) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }

    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            val lu = lockUntil ?: authVm.loginLockedUntilMs
            if (lu != null && lu > now) delay(1000) else delay(5000)
        }
    }

    LaunchedEffect(authVm.messages) {
        authVm.messages.collect { msg ->
            val text = when (msg) {
                is Msg.Res -> context.getString(msg.resId)
                is Msg.Text -> msg.message
            }
            snackbar.showSnackbar(text)
        }
    }

    fun submit() {
        emailError = Validators.email(email)
        passwordError = Validators.required(password)
        if (emailError != null || passwordError != null) return
        busy = true
        authVm.login(email, password)
        busy = false
    }

    // observe lock state changes from VM
    LaunchedEffect(authVm.loginLockedUntilMs) {
        authVm.loginLockedUntilMs?.let { lockUntil = it }
    }
    val effectiveLock = lockUntil ?: authVm.loginLockedUntilMs
    val locked = effectiveLock != null && effectiveLock > now
    val lockMinutes = effectiveLock?.let { ((it - now) / 60000).toInt().coerceAtLeast(1) } ?: 0

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { inner ->
        if (isWideScreen) {
            Row(Modifier.fillMaxSize().padding(inner)) {
                // Left: branding panel (spec 0.7 split-screen login)
                Box(
                    Modifier.weight(1f).fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(R.drawable.login_banner),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                    Column(
                        Modifier.padding(32.dp),
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            stringResource(R.string.app_name),
                            style = MaterialTheme.typography.headlineLarge,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.tagline),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
                LoginForm(
                    modifier = Modifier.weight(1f).fillMaxSize(),
                    showBanner = false,
                    email = email, onEmail = { email = it },
                    password = password, onPassword = { password = it },
                    emailError = emailError, passwordError = passwordError,
                    busy = busy, locked = locked, lockMinutes = lockMinutes,
                    onSubmit = { submit() }
                )
            }
        } else {
            LoginForm(
                modifier = Modifier.fillMaxSize().padding(inner),
                email = email, onEmail = { email = it },
                password = password, onPassword = { password = it },
                emailError = emailError, passwordError = passwordError,
                busy = busy, locked = locked, lockMinutes = lockMinutes,
                onSubmit = { submit() }
            )
        }
    }
}

@Composable
private fun LoginForm(
    modifier: Modifier = Modifier,
    showBanner: Boolean = true,
    email: String,
    onEmail: (String) -> Unit,
    password: String,
    onPassword: (String) -> Unit,
    emailError: String?,
    passwordError: String?,
    busy: Boolean,
    locked: Boolean,
    lockMinutes: Int,
    onSubmit: () -> Unit
) {
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        if (showBanner) {
            Image(
                painter = painterResource(R.drawable.login_banner),
                contentDescription = null,
                modifier = Modifier.fillMaxWidth().height(140.dp),
                contentScale = ContentScale.Crop
            )
            Spacer(Modifier.height(16.dp))
        }
        Text(stringResource(R.string.login_welcome), style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.primary)
        Text(stringResource(R.string.login_subtitle), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(16.dp)) {
                LabeledField(
                    label = stringResource(R.string.email),
                    value = email,
                    onValueChange = onEmail,
                    error = emailError?.let { stringResource(stringRes(it)) },
                    keyboardType = KeyboardType.Email
                )
                Spacer(Modifier.height(12.dp))
                PasswordField(
                    label = stringResource(R.string.password),
                    value = password,
                    onValueChange = onPassword,
                    error = passwordError?.let { stringResource(stringRes(it)) }
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = onSubmit,
                    enabled = !busy && !locked,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        if (locked)
                            stringResource(R.string.account_locked, lockMinutes)
                        else if (busy)
                            stringResource(R.string.signing_in)
                        else
                            stringResource(R.string.sign_in)
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.demo_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { onEmail(Config.DEMO_SUPERADMIN_EMAIL); onPassword(Config.DEMO_PASSWORD) },
                        modifier = Modifier.weight(1f)
                    ) { Text(stringResource(R.string.demo_superadmin)) }
                    OutlinedButton(
                        onClick = { onEmail(Config.DEMO_ADMIN_EMAIL); onPassword(Config.DEMO_PASSWORD) },
                        modifier = Modifier.weight(1f)
                    ) { Text(stringResource(R.string.demo_admin)) }
                }
            }
        }
    }
}

// helper to map error keys (Validators returns resource-name strings) to ids
private fun stringRes(name: String): Int = when (name) {
    "required_field" -> R.string.required_field
    "invalid_email" -> R.string.invalid_email
    "weak_password" -> R.string.weak_password
    "invalid_amount" -> R.string.invalid_amount
    "invalid_phone" -> R.string.invalid_phone
    else -> R.string.error_generic
}

// LoginForm uses isWideGlobal only to decide banner; keep false in narrow form
private val isWideGlobal: Boolean get() = false
