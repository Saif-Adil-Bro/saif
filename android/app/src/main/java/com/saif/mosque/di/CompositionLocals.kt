package com.saif.mosque.di

import androidx.compose.runtime.staticCompositionLocalOf

val LocalContainer = staticCompositionLocalOf<AppContainer> {
    error("AppContainer not provided")
}
