package com.saif.mosque.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.window.DialogProperties
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.rememberDatePickerState
import com.saif.mosque.R
import com.saif.mosque.util.Format
import com.saif.mosque.util.UiState

// ---------- state boxes ----------

@Composable
fun <T> StateBox(
    state: UiState<T>,
    onRetry: (() -> Unit)? = null,
    content: @Composable (T) -> Unit
) {
    when (state) {
        is UiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        }
        is UiState.Error -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(state.message, color = MaterialTheme.colorScheme.error)
                if (onRetry != null) {
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onRetry) { Text(stringResource(R.string.retry)) }
                }
            }
        }
        is UiState.Success -> content(state.data)
    }
}

@Composable
fun EmptyBox(text: String = stringResource(R.string.empty_list)) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp, horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(Modifier.size(52.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(18.dp)),
            contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Inbox, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        }
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
fun StatCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colorScheme.primary
) {
    Card(modifier = modifier, shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleMedium, color = valueColor,
                fontWeight = FontWeight.Bold)
        }
    }
}

data class StatItem(val label: String, val value: String, val color: Color? = null)

/** Two columns where space allows, one column for narrow or enlarged text. */
@Composable
fun StatsGrid(items: List<StatItem>, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val fontScale = androidx.compose.ui.platform.LocalDensity.current.fontScale
        val columns = if (maxWidth < 340.dp || fontScale >= 1.35f) 1 else 2
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items.chunked(columns).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { item ->
                        StatCard(item.label, item.value, Modifier.weight(1f),
                            item.color ?: MaterialTheme.colorScheme.primary)
                    }
                    if (row.size < columns) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onBackground, modifier = modifier.padding(top = 12.dp, bottom = 4.dp))
}

@Composable
fun MoneyText(amount: Double, color: Color = MaterialTheme.colorScheme.onSurface) {
    Text(Format.money(amount), style = MaterialTheme.typography.titleMedium,
        color = color, fontWeight = FontWeight.SemiBold)
}

/** A flexible, vertically structured record: long names and amounts never compete with actions. */
@Composable
fun RecordCard(
    title: String,
    supporting: String = "",
    amount: String? = null,
    amountLabel: String? = null,
    amountColor: Color = MaterialTheme.colorScheme.onSurface,
    status: String? = null,
    onEdit: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    archive: Boolean = false,
    deleteLabel: String = stringResource(R.string.delete)
) {
    Card(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 14.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f).padding(top = 5.dp, end = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    if (supporting.isNotBlank()) Text(supporting, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (status != null) Text(status, style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error)
                }
                if (onEdit != null) IconButton(onClick = onEdit) {
                    Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.edit),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (onDelete != null) IconButton(onClick = onDelete) {
                    Icon(if (archive) Icons.Filled.Archive else Icons.Filled.DeleteOutline,
                        contentDescription = deleteLabel,
                        tint = if (archive) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
                }
            }
            if (amount != null) {
                Spacer(Modifier.height(8.dp))
                if (amountLabel != null) Text(amountLabel, style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(amount, style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold, color = amountColor)
            }
        }
    }
}

// ---------- form fields ----------

@Composable
fun LabeledField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    error: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    singleLine: Boolean = true,
    readOnly: Boolean = false,
    trailingIcon: (@Composable () -> Unit)? = null,
    enabled: Boolean = true
) {
    val message = when (error) {
        "required", "required_field" -> stringResource(R.string.required_field)
        "invalid_email" -> stringResource(R.string.invalid_email)
        "weak_password" -> stringResource(R.string.weak_password)
        "invalid_amount", "amount" -> stringResource(R.string.invalid_amount)
        "phone", "invalid_phone" -> stringResource(R.string.invalid_phone)
        "same" -> stringResource(R.string.same_account_error)
        else -> error
    }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = modifier.fillMaxWidth(),
        isError = message != null,
        supportingText = message?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        singleLine = singleLine,
        readOnly = readOnly,
        enabled = enabled,
        trailingIcon = trailingIcon
    )
}

@Composable
fun PasswordField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    error: String? = null,
    enabled: Boolean = true
) {
    var visible by remember { mutableStateOf(false) }
    val message = when (error) {
        "weak_password" -> stringResource(R.string.weak_password)
        "required_field", "required" -> stringResource(R.string.required_field)
        else -> error
    }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = modifier.fillMaxWidth(),
        isError = message != null,
        supportingText = message?.let { { Text(it) } },
        enabled = enabled,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            IconButton(enabled = enabled, onClick = { visible = !visible }) {
                Icon(if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = stringResource(if (visible) R.string.hide_password else R.string.show_password))
            }
        }
    )
}

/** Date field with a material3 DatePicker dialog. Value is ISO yyyy-MM-dd. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun DatePickerField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    var show by remember { mutableStateOf(false) }
    val pickerState = rememberDatePickerState(
        initialSelectedDateMillis = Format.isoToEpochMilli(value.ifBlank { Format.todayIso() })
    )
    OutlinedTextField(
        value = value,
        onValueChange = {},
        readOnly = true,
        enabled = enabled,
        label = { Text(label) },
        modifier = modifier.fillMaxWidth(),
        trailingIcon = { IconButton(enabled = enabled, onClick = { show = true }) {
            Icon(Icons.Filled.CalendarMonth, contentDescription = stringResource(R.string.date))
        } }
    )
    if (show) {
        DatePickerDialog(
            onDismissRequest = { show = false },
            confirmButton = {
                TextButton(onClick = {
                    show = false
                    pickerState.selectedDateMillis?.let { onValueChange(Format.epochMilliToIso(it)) }
                }) { Text(stringResource(R.string.yes)) }
            },
            dismissButton = {
                TextButton(onClick = { show = false }) { Text(stringResource(R.string.cancel)) }
            }
        ) { DatePicker(state = pickerState) }
    }
}

// ---------- dialogs ----------

val FullWidthDialogProperties = DialogProperties(usePlatformDefaultWidth = false)

fun Modifier.fullWidthDialog(horizontalPadding: Int = 8): Modifier =
    this.fillMaxWidth().padding(horizontal = horizontalPadding.dp)

@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmLabel: String = stringResource(R.string.delete),
    enabled: Boolean = true
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = FullWidthDialogProperties,
        modifier = Modifier.fullWidthDialog(16),
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(enabled = enabled, onClick = { onConfirm(); onDismiss() }) {
                Text(confirmLabel, color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

@Composable
fun InfoDialog(title: String, message: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = FullWidthDialogProperties,
        modifier = Modifier.fullWidthDialog(16),
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.yes)) }
        }
    )
}

// ---------- misc ----------

@Composable
fun RowSpacer(width: Int = 8) = Spacer(Modifier.width(width.dp))
@Composable
fun ColSpacer(height: Int = 8) = Spacer(Modifier.height(height.dp))

@Composable
fun CenteredProgress() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}
