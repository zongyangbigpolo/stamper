package com.stamper.app.speech

import android.content.Context

object OfflineSpeechSupport : OfflineSpeechProvider {
    override fun isAvailable(context: Context): Boolean = OfflineAsr.isBundled(context)

    override fun preload(context: Context) = OfflineAsr.preloadAsync(context)

    override fun create(context: Context, onFinalResult: (String) -> Unit): SpeechEngine =
        OfflineSpeechHelper(context, onFinalResult)
}
