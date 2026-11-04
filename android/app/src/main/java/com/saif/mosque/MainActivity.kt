package com.saif.mosque

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.currentWindowAdaptiveInfo
import androidx.compose.runtime.CompositionLocalProvider
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
                val widthClass = currentWindowAdaptiveInfo().windowSizeClass.widthSizeClass
                CompositionLocalProvider(LocalContainer provides container) {
                    MosqueNavHost(container, widthClass == WindowWidthSizeClass.Expanded)
                }
            }
        }
    }
}
