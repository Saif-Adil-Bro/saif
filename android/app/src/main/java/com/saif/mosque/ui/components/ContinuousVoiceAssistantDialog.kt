package com.saif.mosque.ui.components

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.saif.mosque.R

@Composable
fun ContinuousVoiceAssistantDialog(
    onDismiss: () -> Unit,
    onProcessText: (String) -> Unit,
    onFallbackSystemSpeech: (() -> Unit)? = null
) {
    val context = LocalContext.current

    var hasAudioPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasAudioPermission = isGranted
    }

    LaunchedEffect(Unit) {
        if (!hasAudioPermission) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    val isRecognitionAvailable = remember {
        SpeechRecognizer.isRecognitionAvailable(context)
    }

    var isListening by remember { mutableStateOf(hasAudioPermission && isRecognitionAvailable) }
    var recognizedText by remember { mutableStateOf("") }
    var partialText by remember { mutableStateOf("") }
    var soundLevel by remember { mutableFloatStateOf(0f) }
    var recognizerStatus by remember { mutableStateOf(context.getString(R.string.voice_assistant_listening)) }

    fun buildSpeechIntent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "bn-BD")
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra("android.speech.extra.DICTATION_MODE", true)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 10000L)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 10000L)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
    }

    // Keep an active SpeechRecognizer instance
    val speechRecognizer = remember {
        if (isRecognitionAvailable) {
            runCatching { SpeechRecognizer.createSpeechRecognizer(context) }.getOrNull()
        } else null
    }

    DisposableEffect(speechRecognizer) {
        onDispose {
            try {
                speechRecognizer?.stopListening()
                speechRecognizer?.cancel()
                speechRecognizer?.destroy()
            } catch (_: Exception) {}
        }
    }

    fun safeStartListening() {
        if (speechRecognizer != null && hasAudioPermission && isListening) {
            try {
                speechRecognizer.startListening(buildSpeechIntent())
                recognizerStatus = context.getString(R.string.voice_assistant_listening)
            } catch (_: Exception) {
                // If busy, cancel and retry once
                try {
                    speechRecognizer.cancel()
                    speechRecognizer.startListening(buildSpeechIntent())
                } catch (_: Exception) {}
            }
        }
    }

    LaunchedEffect(speechRecognizer, hasAudioPermission, isListening) {
        if (speechRecognizer != null && hasAudioPermission && isListening) {
            val listener = object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    recognizerStatus = context.getString(R.string.voice_assistant_listening)
                }

                override fun onBeginningOfSpeech() {
                    recognizerStatus = "রেকর্ড হচ্ছে…"
                }

                override fun onRmsChanged(rmsdB: Float) {
                    soundLevel = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
                }

                override fun onBufferReceived(buffer: ByteArray?) {}

                override fun onEndOfSpeech() {
                    soundLevel = 0f
                }

                override fun onError(error: Int) {
                    soundLevel = 0f
                    if (isListening) {
                        when (error) {
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                            SpeechRecognizer.ERROR_NO_MATCH -> {
                                // User paused for 3-5 seconds; seamlessly restart without closing!
                                safeStartListening()
                            }
                            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                                try {
                                    speechRecognizer.cancel()
                                    safeStartListening()
                                } catch (_: Exception) {}
                            }
                            else -> {
                                // For other temporary errors, retry restart gently
                                safeStartListening()
                            }
                        }
                    }
                }

                override fun onResults(results: Bundle?) {
                    soundLevel = 0f
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val top = matches?.firstOrNull()?.trim()
                    if (!top.isNullOrBlank()) {
                        recognizedText = if (recognizedText.isBlank()) {
                            top
                        } else {
                            recognizedText.trimEnd() + ", " + top
                        }
                    }
                    partialText = ""
                    // Keep listening continuously across pauses
                    if (isListening) {
                        safeStartListening()
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    partialText = matches?.firstOrNull()?.trim() ?: ""
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            }

            speechRecognizer.setRecognitionListener(listener)
            safeStartListening()
        } else {
            try {
                speechRecognizer?.stopListening()
            } catch (_: Exception) {}
            recognizerStatus = context.getString(R.string.voice_assistant_paused)
        }
    }

    // Pulse animation for mic
    val infiniteTransition = rememberInfiniteTransition(label = "pulseTransition")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isListening) 1.25f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(850, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    AlertDialog(
        onDismissRequest = {
            try {
                speechRecognizer?.stopListening()
                speechRecognizer?.cancel()
            } catch (_: Exception) {}
            onDismiss()
        },
        properties = FullWidthDialogProperties,
        modifier = Modifier.fullWidthDialog(8),
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Filled.Mic,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(
                            text = stringResource(R.string.voice_assistant_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = stringResource(R.string.voice_assistant_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                IconButton(onClick = {
                    try {
                        speechRecognizer?.stopListening()
                        speechRecognizer?.cancel()
                    } catch (_: Exception) {}
                    onDismiss()
                }) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.cancel))
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // If permission is missing
                if (!hasAudioPermission) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Text(
                                text = stringResource(R.string.voice_permission_rationale),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Spacer(Modifier.height(8.dp))
                            Button(
                                onClick = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error
                                )
                            ) {
                                Text(stringResource(R.string.voice_grant_permission))
                            }
                        }
                    }
                } else if (!isRecognitionAvailable || speechRecognizer == null) {
                    // SpeechRecognizer unavailable on this device (e.g. no Google Speech Service)
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.4f)
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Text(
                                "আপনার ডিভাইসে ব্যাকগ্রাউন্ড ভয়েস সার্ভিস প্রস্তুত পাওয়া যায়নি। আপনি সাধারণ ডায়ালগ বা কিবোর্ড দিয়ে লিখতে পারেন।",
                                style = MaterialTheme.typography.bodySmall
                            )
                            if (onFallbackSystemSpeech != null) {
                                Spacer(Modifier.height(8.dp))
                                FilledTonalButton(onClick = {
                                    onDismiss()
                                    onFallbackSystemSpeech()
                                }) {
                                    Text(stringResource(R.string.voice_fallback_intent))
                                }
                            }
                        }
                    }
                } else {
                    // Microphone Visualizer & Control Area
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (isListening)
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                            else
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                        ),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            // Pulsing Mic Button
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.size(80.dp)
                            ) {
                                if (isListening) {
                                    Box(
                                        modifier = Modifier
                                            .size(72.dp)
                                            .scale(pulseScale)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.22f))
                                    )
                                }
                                Surface(
                                    onClick = { isListening = !isListening },
                                    shape = CircleShape,
                                    color = if (isListening) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                    modifier = Modifier.size(56.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            if (isListening) Icons.Filled.Mic else Icons.Filled.MicOff,
                                            contentDescription = if (isListening) "Mic Active" else "Mic Muted",
                                            tint = Color.White,
                                            modifier = Modifier.size(28.dp)
                                        )
                                    }
                                }
                            }

                            Spacer(Modifier.height(8.dp))

                            Text(
                                text = recognizerStatus,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = if (isListening) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            // Live partial preview while speaking
                            AnimatedVisibility(visible = partialText.isNotBlank()) {
                                Surface(
                                    color = MaterialTheme.colorScheme.surface,
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier
                                        .padding(top = 8.dp)
                                        .border(
                                            1.dp,
                                            MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                                            RoundedCornerShape(8.dp)
                                        )
                                ) {
                                    Text(
                                        text = "🎙️ $partialText",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }
                }

                // Voice text editor area
                Column {
                    Text(
                        text = "জমা হওয়া টেক্সট (প্রয়োজনে কিবোর্ডে এডিট করুন):",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )

                    OutlinedTextField(
                        value = recognizedText,
                        onValueChange = { recognizedText = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(130.dp),
                        placeholder = {
                            Text(
                                stringResource(R.string.voice_input_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        },
                        shape = RoundedCornerShape(12.dp),
                        trailingIcon = {
                            if (recognizedText.isNotBlank()) {
                                IconButton(onClick = {
                                    recognizedText = ""
                                    partialText = ""
                                }) {
                                    Icon(
                                        Icons.Filled.Clear,
                                        contentDescription = stringResource(R.string.voice_btn_clear),
                                        tint = MaterialTheme.colorScheme.outline
                                    )
                                }
                            }
                        }
                    )
                }

                // Tip helper
                Text(
                    text = "💡 উদাহরণ: \"আলামিন ৫০০, রবিউল ২০০, জসিম ১০০০\"। নাম ও টাকার মাঝে বিরতি নিলেও এটি বন্ধ হবে না। সব বলা শেষ হলে নিচের 'যাচাই ও প্রসেস করুন' বাটনে চাপ দিন।",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 18.sp
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    try {
                        speechRecognizer?.stopListening()
                        speechRecognizer?.cancel()
                    } catch (_: Exception) {}

                    val combined = if (partialText.isNotBlank()) {
                        if (recognizedText.isBlank()) partialText else "$recognizedText, $partialText"
                    } else {
                        recognizedText
                    }.trim()

                    if (combined.isNotBlank()) {
                        onProcessText(combined)
                    }
                    onDismiss()
                },
                enabled = recognizedText.isNotBlank() || partialText.isNotBlank()
            ) {
                Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.voice_btn_done))
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (recognizedText.isNotBlank()) {
                    OutlinedButton(onClick = {
                        recognizedText = ""
                        partialText = ""
                    }) {
                        Text(stringResource(R.string.voice_btn_clear))
                    }
                }
                TextButton(onClick = {
                    try {
                        speechRecognizer?.stopListening()
                        speechRecognizer?.cancel()
                    } catch (_: Exception) {}
                    onDismiss()
                }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        }
    )
}
