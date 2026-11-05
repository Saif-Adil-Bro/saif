package com.saif.mosque.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.defaultMinSize
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
    val busy = authVm.busy
    var now by remember { mutableStateOf(System.currentTimeMillis()) }

    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            val lu = if (authVm.loginLockedEmail == email.trim().lowercase()) authVm.loginLockedUntilMs else null
            if (lu != null && lu > now) delay(1000) else delay(5000)
        }
    }

    LaunchedEffect(authVm.messages) {
        authVm.messages.collect { msg ->
            val text = when (msg) {
                is Msg.Res -> context.getString(msg.resId) + (msg.detail?.let { " [$it]" } ?: "")
                is Msg.Text -> msg.message
            }
            snackbar.showSnackbar(text)
        }
    }

    fun submit() {
        emailError = Validators.email(email)
        passwordError = Validators.required(password)
        if (emailError != null || passwordError != null) return
        authVm.login(email, password)
    }

    val effectiveLock = if (authVm.loginLockedEmail == email.trim().lowercase()) authVm.loginLockedUntilMs else null
    val locked = effectiveLock != null && effectiveLock > now
    val lockMinutes = effectiveLock?.let { ((it - now) / 60000).toInt().coerceAtLeast(1) } ?: 0

    Scaffold(snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background) { inner ->
        if (isWideScreen) {
            Row(Modifier.fillMaxSize().padding(inner)) {
                BrandPanel(Modifier.weight(1f).fillMaxSize().padding(20.dp), compact = false)
                LoginForm(Modifier.weight(1f).fillMaxSize().imePadding(), showBanner = false,
                    email = email, onEmail = { email = it }, password = password, onPassword = { password = it },
                    emailError = emailError, passwordError = passwordError,
                    busy = busy, locked = locked, lockMinutes = lockMinutes, onSubmit = { submit() })
            }
        } else {
            LoginForm(Modifier.fillMaxSize().padding(inner).imePadding(), showBanner = true,
                email = email, onEmail = { email = it }, password = password, onPassword = { password = it },
                emailError = emailError, passwordError = passwordError,
                busy = busy, locked = locked, lockMinutes = lockMinutes, onSubmit = { submit() })
        }
    }
}

@Composable
private fun BrandPanel(modifier: Modifier = Modifier, compact: Boolean) {
    Box(modifier.clip(RoundedCornerShape(28.dp)).background(MaterialTheme.colorScheme.primary)) {
        Image(painter = painterResource(R.drawable.login_banner), contentDescription = null,
            contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(
            Color(0x22103E33), Color(0xE6103E33)))))
        Column(Modifier.align(Alignment.BottomStart).padding(if (compact) 20.dp else 32.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.app_name),
                style = if (compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineMedium,
                color = Color.White)
            Text(stringResource(R.string.tagline), style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.88f))
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun LoginForm(
    modifier: Modifier = Modifier,
    showBanner: Boolean = true,
    email: String, onEmail: (String) -> Unit,
    password: String, onPassword: (String) -> Unit,
    emailError: String?, passwordError: String?,
    busy: Boolean, locked: Boolean, lockMinutes: Int, onSubmit: () -> Unit
) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 480.dp).fillMaxWidth()
            .verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (showBanner) BrandPanel(Modifier.fillMaxWidth().height(190.dp), compact = true)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.login_welcome), style = MaterialTheme.typography.headlineMedium)
                Text(stringResource(R.string.login_subtitle), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Card(shape = MaterialTheme.shapes.extraLarge,
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    LabeledField(label = stringResource(R.string.email), value = email, onValueChange = onEmail,
                        error = emailError?.let { stringResource(stringRes(it)) }, keyboardType = KeyboardType.Email)
                    PasswordField(label = stringResource(R.string.password), value = password, onValueChange = onPassword,
                        error = passwordError?.let { stringResource(stringRes(it)) })
                    if (locked) Text(stringResource(R.string.account_locked, lockMinutes),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    Button(onClick = onSubmit, enabled = !busy && !locked,
                        modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 52.dp),
                        shape = MaterialTheme.shapes.medium) {
                        Text(stringResource(if (busy) R.string.signing_in else R.string.sign_in))
                    }
                }
            }
            if (com.saif.mosque.BuildConfig.DEBUG) {
                Text(stringResource(R.string.demo_note), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onEmail(Config.DEMO_SUPERADMIN_EMAIL); onPassword(Config.DEMO_PASSWORD) }) {
                        Text(stringResource(R.string.demo_superadmin))
                    }
                    OutlinedButton(onClick = { onEmail(Config.DEMO_ADMIN_EMAIL); onPassword(Config.DEMO_PASSWORD) }) {
                        Text(stringResource(R.string.demo_admin))
                    }
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
