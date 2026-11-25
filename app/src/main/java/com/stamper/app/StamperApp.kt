package com.stamper.app

import android.app.Application
import com.stamper.app.data.SettingsRepository
import com.stamper.app.data.StamperDatabase
import com.stamper.app.data.StamperRepository

class StamperApp : Application() {

    lateinit var repository: StamperRepository
        private set

    override fun onCreate() {
        super.onCreate()
        val database = StamperDatabase(this)
        val settingsRepository = SettingsRepository(this)
        repository = StamperRepository(
            appContext = this,
            database = database,
            settingsRepository = settingsRepository
        )
        // Warm up the offline ASR model so the first dictation after a shot is instant.
        com.stamper.app.speech.OfflineSpeechSupport.preload(this)
    }
}
