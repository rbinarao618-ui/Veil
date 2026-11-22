package com.example

import android.app.Application
import com.example.data.SettingsDataStore
import com.example.data.ShaderDatabase
import com.example.data.ShaderRepository

class ShaderVeilApp : Application() {
    val database by lazy { ShaderDatabase.getInstance(this) }
    val repository by lazy { ShaderRepository(database.shaderDao()) }
    val settingsStore by lazy { SettingsDataStore(this) }
}
