package com.saif.mosque

import android.app.Application
import com.saif.mosque.di.AppContainer

class MosqueApp : Application() {
    val container by lazy { AppContainer(this) }
}
