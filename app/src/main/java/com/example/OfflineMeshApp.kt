package com.example

import android.app.Application
import com.example.di.AppContainer

class OfflineMeshApp : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}
