package com.stamper.app.speech

import android.content.Context

/** Lite build: no bundled ASR model, always use the system recognizer. */
object OfflineSpeechSupport : OfflineSpeechProvider {
    override fun isAvailable(context: Context): Boolean = false

    override fun preload(context: Context) = Unit

    override fun create(context: Context, onFinalResult: (String) -> Unit): SpeechEngine =
        error("Offline speech is not bundled in the lite build")
}
