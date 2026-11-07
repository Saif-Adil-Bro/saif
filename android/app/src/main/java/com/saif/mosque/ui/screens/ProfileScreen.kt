package com.saif.mosque.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockReset
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Mosque
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Mail
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.saif.mosque.R
import com.saif.mosque.data.model.AppUser
import com.saif.mosque.data.model.Role
import com.saif.mosque.di.AppContainer
import com.saif.mosque.ui.components.ConfirmDialog
import com.saif.mosque.ui.components.FullWidthDialogProperties
import com.saif.mosque.ui.components.LabeledField
import com.saif.mosque.ui.components.PasswordField
import com.saif.mosque.ui.components.SectionTitle
import com.saif.mosque.ui.components.fullWidthDialog
import com.saif.mosque.ui.navigation.Screen
import com.saif.mosque.ui.viewmodel.AuthViewModel
import com.saif.mosque.util.Permissions
import com.saif.mosque.util.Validators
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

@Composable
fun ProfileScreen(
    nav: NavHostController,
    container: AppContainer,
    authVm: AuthViewModel,
    user: AppUser
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var currentUser by remember(user) { mutableStateOf(user) }

    // Live watch for user profile changes
    val profileFlow = remember(user.id) {
        container.authRepository.watchProfile(user.id).catch { emit(null) }
    }
    val watchedUser by profileFlow.collectAsState(initial = user)
    val activeUser = watchedUser ?: currentUser

    // Mosque name flow
    val mosqueFlow = remember(activeUser.mosqueId) {
        activeUser.mosqueId?.let { container.ledgerRepository.observeMosque(it).catch { emit(null) } }
    }
    val mosqueInfo by mosqueFlow?.collectAsState(initial = null) ?: remember { mutableStateOf(null) }

    var showEditProfileDialog by remember { mutableStateOf(false) }
    var showChangePasswordDialog by remember { mutableStateOf(false) }
    var showSendResetLinkDialog by remember { mutableStateOf(false) }
    var confirmLogout by remember { mutableStateOf(false) }

    val canManageMembers = activeUser.role in setOf(Role.ADMIN, Role.PRESIDENT, Role.SECRETARY, Role.SUPERADMIN)

    Scaffold(
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 1. Profile Header Hero Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                    ),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Avatar Initial Box
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(72.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = activeUser.name.take(1).uppercase().ifBlank { "M" },
                                    style = MaterialTheme.typography.headlineMedium,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        Text(
                            text = activeUser.name.ifBlank { "—" },
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        // Role Badge
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.primary
                        ) {
                            Text(
                                text = roleLabel(activeUser.role),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimary,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)
                            )
                        }

                        if (activeUser.designation.isNotBlank()) {
                            Text(
                                text = activeUser.designation,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Spacer(Modifier.height(4.dp))

                        // Mosque & Contact Info
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            ProfileInfoRow(
                                icon = Icons.Filled.Mail,
                                label = "ইমেইল",
                                value = activeUser.email
                            )

                            ProfileInfoRow(
                                icon = Icons.Filled.Phone,
                                label = "মোবাইল নং",
                                value = activeUser.phone.ifBlank { "যোগ করা হয়নি" }
                            )

                            if (activeUser.mosqueId != null) {
                                ProfileInfoRow(
                                    icon = Icons.Filled.Mosque,
                                    label = "মসজিদ",
                                    value = mosqueInfo?.name ?: "লোড হচ্ছে…"
                                )
                            }
                        }
                    }
                }
            }

            // 2. Personal Profile Settings
            item {
                SectionTitle("ব্যক্তিগত প্রোফাইল সেটিংস")
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(Modifier.fillMaxWidth()) {
                        ProfileSettingActionRow(
                            icon = Icons.Filled.Edit,
                            title = "নাম ও মোবাইল পরিবর্তন",
                            subtitle = "নিজের নাম ও মোবাইল নম্বর সম্পাদনা করুন",
                            onClick = { showEditProfileDialog = true }
                        )

                        androidx.compose.material3.Divider(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )

                        ProfileSettingActionRow(
                            icon = Icons.Filled.Lock,
                            title = "পাসওয়ার্ড পরিবর্তন",
                            subtitle = "বর্তমান পাসওয়ার্ড দিয়ে নতুন পাসওয়ার্ড সেট করুন",
                            onClick = { showChangePasswordDialog = true }
                        )

                        androidx.compose.material3.Divider(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )

                        ProfileSettingActionRow(
                            icon = Icons.Filled.LockReset,
                            title = "পাসওয়ার্ড রিসেট ইমেইল",
                            subtitle = "ইমেইলে পাসওয়ার্ড রিসেট লিঙ্ক পাঠাতে ট্যাপ করুন",
                            onClick = { showSendResetLinkDialog = true }
                        )
                    }
                }
            }

            // 3. Admin & Member Management Section (If Admin/President/Secretary)
            if (canManageMembers) {
                item {
                    SectionTitle("প্রশাসন ও সদস্য ব্যবস্থাপনা")
                }

                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f)
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(
                                    Icons.Filled.Group,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = "কমিটি ও সদস্যদের প্রোফাইল নিয়ন্ত্রণ",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Text(
                                text = "মসজিদ অ্যাডমিন হিসেবে আপনি কমিটির অন্যান্য সদস্যদের নাম, মোবাইল নম্বর, পদবি/দায়িত্ব পরিবর্তন ও অ্যাকাউন্ট সক্রিয়/নিষ্ক্রিয় করতে পারবেন।",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(4.dp))
                            Button(
                                onClick = { nav.navigate(Screen.Members.route) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Filled.Group, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.manage_members_btn))
                            }
                        }
                    }
                }
            }

            // 4. Session & Logout
            item {
                SectionTitle("অ্যাকাউন্ট")
            }

            item {
                OutlinedButton(
                    onClick = { confirmLogout = true },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(Icons.Filled.Logout, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.logout), fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }

    // Edit Name & Phone Dialog
    if (showEditProfileDialog) {
        EditProfileDialog(
            user = activeUser,
            onDismiss = { showEditProfileDialog = false },
            onSave = { newName, newPhone ->
                scope.launch {
                    val result = container.authRepository.updateProfile(activeUser.id, newName, newPhone)
                    if (result.isSuccess) {
                        currentUser = currentUser.copy(name = newName, phone = newPhone)
                        showEditProfileDialog = false
                        snackbarHostState.showSnackbar(context.getString(R.string.profile_updated_ok))
                    } else {
                        val msg = result.exceptionOrNull()?.message ?: context.getString(R.string.error_generic)
                        snackbarHostState.showSnackbar(msg)
                    }
                }
            }
        )
    }

    // Change Password Dialog
    if (showChangePasswordDialog) {
        ChangePasswordDialog(
            onDismiss = { showChangePasswordDialog = false },
            onSave = { currPass, newPass ->
                scope.launch {
                    val result = container.authRepository.changePassword(currPass, newPass)
                    if (result.isSuccess) {
                        showChangePasswordDialog = false
                        snackbarHostState.showSnackbar(context.getString(R.string.password_changed_ok))
                    } else {
                        val msg = result.exceptionOrNull()?.message ?: context.getString(R.string.error_generic)
                        snackbarHostState.showSnackbar(msg)
                    }
                }
            }
        )
    }

    // Send Reset Link Confirmation Dialog
    if (showSendResetLinkDialog) {
        AlertDialog(
            onDismissRequest = { showSendResetLinkDialog = false },
            properties = FullWidthDialogProperties,
            modifier = Modifier.fullWidthDialog(16),
            title = { Text(stringResource(R.string.send_reset_link)) },
            text = {
                Text("${activeUser.email} ঠিকানায় পাসওয়ার্ড রিসেট করার অফিশিয়াল লিঙ্ক পাঠানো হবে। আপনি কি নিশ্চিত?")
            },
            confirmButton = {
                Button(onClick = {
                    showSendResetLinkDialog = false
                    scope.launch {
                        val result = container.authRepository.sendPasswordResetEmail(activeUser.email)
                        if (result.isSuccess) {
                            snackbarHostState.showSnackbar(context.getString(R.string.reset_email_sent))
                        } else {
                            val msg = result.exceptionOrNull()?.message ?: context.getString(R.string.error_generic)
                            snackbarHostState.showSnackbar(msg)
                        }
                    }
                }) {
                    Text("লিঙ্ক পাঠান")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSendResetLinkDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    // Confirm Logout Dialog
    if (confirmLogout) {
        ConfirmDialog(
            title = stringResource(R.string.logout),
            message = stringResource(R.string.log_out_confirm),
            confirmLabel = stringResource(R.string.logout),
            onConfirm = { authVm.logout() },
            onDismiss = { confirmLogout = false }
        )
    }
}

@Composable
private fun ProfileInfoRow(
    icon: ImageVector,
    label: String,
    value: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp)
        )
        Text(
            text = "$label:",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(76.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun ProfileSettingActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .androidx.compose.foundation.clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.weight(1f)
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                modifier = Modifier.size(40.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Icon(
            Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun EditProfileDialog(
    user: AppUser,
    onDismiss: () -> Unit,
    onSave: (newName: String, newPhone: String) -> Unit
) {
    var name by remember { mutableStateOf(user.name) }
    var phone by remember { mutableStateOf(user.phone) }
    var attempted by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        properties = FullWidthDialogProperties,
        modifier = Modifier.fullWidthDialog(8),
        title = { Text(stringResource(R.string.edit_profile)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                LabeledField(
                    label = stringResource(R.string.name),
                    value = name,
                    onValueChange = { name = it },
                    error = if (attempted) Validators.required(name) else null,
                    enabled = !isSaving
                )

                LabeledField(
                    label = stringResource(R.string.phone),
                    value = phone,
                    onValueChange = { phone = it },
                    error = if (attempted && phone.isNotBlank()) Validators.bdPhone(phone) else null,
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Phone,
                    enabled = !isSaving
                )
            }
        },
        confirmButton = {
            Button(
                enabled = !isSaving,
                onClick = {
                    attempted = true
                    if (Validators.required(name) != null) return@Button
                    if (phone.isNotBlank() && Validators.bdPhone(phone) != null) return@Button
                    isSaving = true
                    onSave(name.trim(), phone.trim())
                }
            ) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(
                enabled = !isSaving,
                onClick = onDismiss
            ) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
private fun ChangePasswordDialog(
    onDismiss: () -> Unit,
    onSave: (currPass: String, newPass: String) -> Unit
) {
    var currentPassword by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var attempted by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }

    val mismatch = attempted && newPassword != confirmPassword
    val weakNew = if (attempted) Validators.password(newPassword) else null

    AlertDialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        properties = FullWidthDialogProperties,
        modifier = Modifier.fullWidthDialog(8),
        title = { Text(stringResource(R.string.change_password)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                PasswordField(
                    label = stringResource(R.string.current_password),
                    value = currentPassword,
                    onValueChange = { currentPassword = it },
                    error = if (attempted && currentPassword.isBlank()) "বর্তমান পাসওয়ার্ড দিন" else null,
                    enabled = !isSaving
                )

                PasswordField(
                    label = stringResource(R.string.new_password),
                    value = newPassword,
                    onValueChange = { newPassword = it },
                    error = weakNew,
                    enabled = !isSaving
                )

                PasswordField(
                    label = stringResource(R.string.confirm_password),
                    value = confirmPassword,
                    onValueChange = { confirmPassword = it },
                    error = if (mismatch) stringResource(R.string.passwords_mismatch) else null,
                    enabled = !isSaving
                )
            }
        },
        confirmButton = {
            Button(
                enabled = !isSaving,
                onClick = {
                    attempted = true
                    if (currentPassword.isBlank()) return@Button
                    if (Validators.password(newPassword) != null) return@Button
                    if (newPassword != confirmPassword) return@Button
                    isSaving = true
                    onSave(currentPassword, newPassword)
                }
            ) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(
                enabled = !isSaving,
                onClick = onDismiss
            ) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}
