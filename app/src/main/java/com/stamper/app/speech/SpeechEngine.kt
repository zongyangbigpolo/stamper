package com.stamper.app.speech

import android.content.Context
import kotlinx.coroutines.flow.StateFlow

interface SpeechEngine {
    val state: StateFlow<SpeechUiState>
    fun startListening()
    fun stopListening()
    fun release()
}

/**
 * Flavor bridge: the `full` build ships the bundled offline Chinese ASR model, the `lite` build
 * doesn't and falls back to the phone's system speech recognizer / keyboard voice input.
 * Each flavor provides an [OfflineSpeechProvider] implementation named `OfflineSpeechSupport`.
 */
interface OfflineSpeechProvider {
    fun isAvailable(context: Context): Boolean
    fun preload(context: Context)
    fun create(context: Context, onFinalResult: (String) -> Unit): SpeechEngine
}
