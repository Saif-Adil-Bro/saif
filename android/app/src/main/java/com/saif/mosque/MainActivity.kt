package com.saif.mosque

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.dp
import com.saif.mosque.di.AppContainer
import com.saif.mosque.di.LocalContainer
import com.saif.mosque.ui.navigation.MosqueNavHost
import com.saif.mosque.ui.theme.MosqueTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as MosqueApp).container
        setContent {
            MosqueTheme {
                BoxWithConstraints {
                    // wide screens (tablet / landscape) get the split-screen layout
                    val isWide = maxWidth >= 600.dp
                    CompositionLocalProvider(LocalContainer provides container) {
                        MosqueNavHost(container, isWide)
                    }
                }
            }
        }
    }
}
