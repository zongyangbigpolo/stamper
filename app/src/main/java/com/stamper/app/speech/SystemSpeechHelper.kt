package com.stamper.app.speech

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class SpeechUiState(
    val isAvailable: Boolean = true,
    val isListening: Boolean = false,
    val partialTranscript: String = "",
    val rmsDb: Float = 0f,
    val errorMessage: String? = null,
    val needsVoiceSetup: Boolean = false
)

private sealed interface SpeechCandidate {
    data class ExplicitComponent(val component: ComponentName) : SpeechCandidate
    data object OnDevice : SpeechCandidate
    data object SystemDefault : SpeechCandidate
}

/**
 * Main-thread-safe wrapper around Android's built-in & domestic OEM [SpeechRecognizer] engines.
 *
 * Domestic Chinese ROMs (Xiaomi HyperOS/MIUI, Huawei HarmonyOS/EMUI, Honor, OPPO ColorOS,
 * vivo OriginOS) often return `false` from [SpeechRecognizer.isRecognitionAvailable] even when
 * an OEM speech engine is configured in `Settings.Secure.voice_recognition_service` or installed
 * as a system service. This helper discovers and tries all available native/OEM candidates
 * automatically (including `BIND_INCLUDE_CAPABILITIES` foreground lease for Android 11+/HyperOS),
 * falling back to `ACTION_RECOGNIZE_SPEECH` dialog Activity if needed.
 */
class SystemSpeechHelper(
    context: Context,
    private val onRequestDialogFallback: (() -> Boolean)? = null,
    private val onFinalResult: (String) -> Unit
) : SpeechEngine {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    private var recognizer: SpeechRecognizer? = null
    private var capabilityLease: CapabilityServiceLease? = null
    private var candidates: List<SpeechCandidate> = emptyList()
    private var attemptIndex: Int = 0
    private var generation: Long = 0L
    private var candidateResponded: Boolean = false

    private val _state = MutableStateFlow(SpeechUiState(isAvailable = true))
    override val state: StateFlow<SpeechUiState> = _state.asStateFlow()

    private val silenceWatchdog = Runnable {
        if (!_state.value.isListening) return@Runnable
        if (!candidateResponded) {
            tryNextCandidateOrFail()
        }
    }

    override fun startListening() {
        runOnMain {
            teardownCurrentRecognizer()
            candidates = discoverCandidates(appContext)
            attemptIndex = 0

            if (candidates.isEmpty()) {
                if (hasRecognizerDialogActivity(appContext) && onRequestDialogFallback?.invoke() == true) {
                    return@runOnMain
                }
                _state.update {
                    it.copy(
                        isAvailable = false,
                        isListening = false,
                        needsVoiceSetup = true,
                        errorMessage = "未检测到已启用的系统语音引擎，可点下方按钮开启系统「语音输入」"
                    )
                }
                return@runOnMain
            }

            _state.update {
                it.copy(
                    isAvailable = true,
                    isListening = true,
                    partialTranscript = "",
                    rmsDb = 0f,
                    errorMessage = null,
                    needsVoiceSetup = false
                )
            }
            startCandidateAt(0)
        }
    }

    override fun stopListening() {
        runOnMain {
            mainHandler.removeCallbacks(silenceWatchdog)
            runCatching { recognizer?.stopListening() }
            _state.update { it.copy(isListening = false, rmsDb = 0f) }
        }
    }

    fun clearError() {
        _state.update { it.copy(errorMessage = null) }
    }

    override fun release() {
        runOnMain {
            teardownCurrentRecognizer()
            _state.update { it.copy(isListening = false, partialTranscript = "", rmsDb = 0f) }
        }
    }

    private fun startCandidateAt(index: Int) {
        teardownCurrentRecognizer()
        val candidate = candidates.getOrNull(index)
        if (candidate == null) {
            handleAllCandidatesExhausted()
            return
        }
        attemptIndex = index
        candidateResponded = false
        val currentGen = ++generation

        val launchRecognizer = {
            if (currentGen == generation) {
                val created = runCatching {
                    when (candidate) {
                        is SpeechCandidate.ExplicitComponent ->
                            SpeechRecognizer.createSpeechRecognizer(appContext, candidate.component)
                        SpeechCandidate.OnDevice ->
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext)
                            } else {
                                null
                            }
                        SpeechCandidate.SystemDefault ->
                            SpeechRecognizer.createSpeechRecognizer(appContext)
                    }
                }.getOrNull()

                if (created == null) {
                    tryNextCandidateOrFail()
                } else {
                    recognizer = created
                    attachAndStart(created, candidate, currentGen)
                }
            }
        }

        if (candidate is SpeechCandidate.ExplicitComponent && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val lease = CapabilityServiceLease.tryCreate(
                context = appContext,
                service = candidate.component,
                onConnected = {
                    mainHandler.post {
                        if (currentGen == generation && recognizer == null) {
                            launchRecognizer()
                        }
                    }
                },
                onFailed = {
                    mainHandler.post {
                        if (currentGen == generation && recognizer == null) {
                            launchRecognizer()
                        }
                    }
                }
            )
            if (lease != null && lease.bind()) {
                capabilityLease = lease
                // If connection callback takes >800ms, proceed directly so we don't stall
                mainHandler.postDelayed({
                    if (currentGen == generation && recognizer == null) {
                        launchRecognizer()
                    }
                }, 800L)
                return
            } else {
                lease?.close()
            }
        }

        launchRecognizer()
    }

    private fun attachAndStart(
        rec: SpeechRecognizer,
        candidate: SpeechCandidate,
        expectedGen: Long
    ) {
        rec.setRecognitionListener(object : RecognitionListener {
            private fun isActive(): Boolean = expectedGen == generation && recognizer === rec

            private fun markResponded() {
                if (!candidateResponded) {
                    candidateResponded = true
                    rememberedWorkingCandidate = candidate
                    mainHandler.removeCallbacks(silenceWatchdog)
                }
            }

            override fun onReadyForSpeech(params: Bundle?) {
                if (!isActive()) return
                markResponded()
                _state.update { it.copy(isListening = true, errorMessage = null, needsVoiceSetup = false) }
            }

            override fun onBeginningOfSpeech() {
                if (!isActive()) return
                markResponded()
            }

            override fun onRmsChanged(rmsdB: Float) {
                if (!isActive()) return
                markResponded()
                _state.update { it.copy(rmsDb = rmsdB.coerceAtLeast(0f)) }
            }

            override fun onBufferReceived(buffer: ByteArray?) {
                if (!isActive()) return
                markResponded()
            }

            override fun onEndOfSpeech() {
                if (!isActive()) return
                markResponded()
                _state.update { it.copy(isListening = false, rmsDb = 0f) }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                if (!isActive()) return
                markResponded()
                val partial = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.trim()
                    .orEmpty()
                if (partial.isNotEmpty()) {
                    _state.update { it.copy(partialTranscript = partial) }
                }
            }

            override fun onResults(results: Bundle?) {
                if (!isActive()) return
                markResponded()
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val best = matches?.firstOrNull()?.trim().orEmpty()
                val fallback = _state.value.partialTranscript.trim()
                val resolved = best.ifEmpty { fallback }
                _state.update {
                    it.copy(
                        isListening = false,
                        partialTranscript = "",
                        rmsDb = 0f,
                        errorMessage = null,
                        needsVoiceSetup = false
                    )
                }
                if (resolved.isNotEmpty()) {
                    onFinalResult(resolved)
                }
            }

            override fun onError(error: Int) {
                if (!isActive()) return
                mainHandler.removeCallbacks(silenceWatchdog)

                val partial = _state.value.partialTranscript.trim()
                if (partial.isNotEmpty() &&
                    (error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT || error == SpeechRecognizer.ERROR_NO_MATCH)
                ) {
                    onFinalResult(partial)
                    _state.update {
                        it.copy(isListening = false, partialTranscript = "", rmsDb = 0f, errorMessage = null)
                    }
                    return
                }

                // If this candidate never even started listening or rejects the language/client,
                // transparently fail over to the next candidate on this phone!
                val isUnsupportedLang = error == ERROR_LANGUAGE_NOT_SUPPORTED || error == ERROR_LANGUAGE_UNAVAILABLE
                if ((!candidateResponded || isUnsupportedLang) && attemptIndex + 1 < candidates.size) {
                    startCandidateAt(attemptIndex + 1)
                    return
                }

                if (!candidateResponded && hasRecognizerDialogActivity(appContext)) {
                    teardownCurrentRecognizer()
                    _state.update { it.copy(isListening = false, rmsDb = 0f) }
                    if (onRequestDialogFallback?.invoke() == true) {
                        return
                    }
                }

                val needsSetup = !candidateResponded &&
                    error != SpeechRecognizer.ERROR_NO_MATCH &&
                    error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT

                val message = when (error) {
                    SpeechRecognizer.ERROR_AUDIO -> "麦克风录音异常，请检查系统麦克风总开关或权限"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                        "语音服务缺少麦克风权限，请检查录音权限或系统语音服务权限"
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                        "语音识别需要联网或下载系统离线语音包，请检查网络"
                    SpeechRecognizer.ERROR_NO_MATCH -> "未听清语音内容，请再点麦克风说一次"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "未检测到说话声音，请点右侧麦克风重说"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "语音服务正忙，请稍候一点重试"
                    SpeechRecognizer.ERROR_CLIENT ->
                        if (!candidateResponded) "系统语音服务未响应，请点击下方开启「系统语音输入」" else null
                    else -> "系统语音服务暂不可用 (错误码 $error)，可检查系统「语音输入」设置"
                }
                _state.update {
                    it.copy(
                        isListening = false,
                        partialTranscript = "",
                        rmsDb = 0f,
                        errorMessage = message,
                        needsVoiceSetup = needsSetup
                    )
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName)
        }

        mainHandler.removeCallbacks(silenceWatchdog)
        mainHandler.postDelayed(silenceWatchdog, SILENCE_WATCHDOG_MS)

        try {
            rec.startListening(intent)
        } catch (_: Throwable) {
            tryNextCandidateOrFail()
        }
    }

    private fun tryNextCandidateOrFail() {
        if (attemptIndex + 1 < candidates.size) {
            startCandidateAt(attemptIndex + 1)
        } else {
            handleAllCandidatesExhausted()
        }
    }

    private fun handleAllCandidatesExhausted() {
        teardownCurrentRecognizer()
        if (hasRecognizerDialogActivity(appContext) && onRequestDialogFallback?.invoke() == true) {
            _state.update { it.copy(isListening = false, rmsDb = 0f, errorMessage = null) }
            return
        }
        _state.update {
            it.copy(
                isAvailable = false,
                isListening = false,
                partialTranscript = "",
                rmsDb = 0f,
                needsVoiceSetup = true,
                errorMessage = "手机尚未选定默认「语音输入」引擎，请点下方按钮去系统设置开启"
            )
        }
    }

    private fun teardownCurrentRecognizer() {
        generation++
        mainHandler.removeCallbacks(silenceWatchdog)
        val rec = recognizer
        recognizer = null
        rec?.let {
            runCatching { it.cancel() }
            runCatching { it.destroy() }
        }
        val lease = capabilityLease
        capabilityLease = null
        lease?.close()
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    /**
     * Holds a `BIND_INCLUDE_CAPABILITIES` service connection during recognition on Android 11+,
     * allowing OEM services (especially Xiaomi HyperOS `com.xiaomi.mibrain.speech`) to inherit
     * our foreground app's microphone capability.
     */
    private class CapabilityServiceLease(
        private val context: Context,
        private val service: ComponentName,
        private val onConnected: () -> Unit,
        private val onFailed: () -> Unit
    ) {
        private var bound = false
        private var closed = false

        private val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                if (!closed) onConnected()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                if (!closed) onFailed()
            }

            override fun onNullBinding(name: ComponentName?) {
                if (!closed) onFailed()
            }

            override fun onBindingDied(name: ComponentName?) {
                if (!closed) onFailed()
            }
        }

        fun bind(): Boolean {
            if (closed || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
            val flags = Context.BIND_AUTO_CREATE or Context.BIND_INCLUDE_CAPABILITIES
            bound = runCatching {
                context.bindService(
                    Intent(RecognitionService.SERVICE_INTERFACE).setComponent(service),
                    conn,
                    flags
                )
            }.getOrDefault(false)
            return bound
        }

        fun close() {
            if (closed) return
            closed = true
            if (bound) {
                bound = false
                runCatching { context.unbindService(conn) }
            }
        }

        companion object {
            fun tryCreate(
                context: Context,
                service: ComponentName,
                onConnected: () -> Unit,
                onFailed: () -> Unit
            ): CapabilityServiceLease? {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
                return runCatching {
                    val info = context.packageManager.getServiceInfo(service, 0)
                    if (!info.exported) return null
                    if (!info.permission.isNullOrEmpty() &&
                        context.checkSelfPermission(info.permission) != PackageManager.PERMISSION_GRANTED
                    ) {
                        return null
                    }
                    CapabilityServiceLease(context, service, onConnected, onFailed)
                }.getOrNull()
            }
        }
    }

    companion object {
        private const val SILENCE_WATCHDOG_MS = 2200L
        private const val MAX_CANDIDATES = 5
        private const val ERROR_LANGUAGE_NOT_SUPPORTED = 12
        private const val ERROR_LANGUAGE_UNAVAILABLE = 13

        @Volatile
        private var rememberedWorkingCandidate: SpeechCandidate? = null

        private val KNOWN_OEM_VOICE_PACKAGES = listOf(
            "com.xiaomi.mibrain.speech",
            "com.miui.voiceassist",
            "com.huawei.vassistant",
            "com.huawei.hiai",
            "com.huawei.hms.mlsdk.asr",
            "com.hihonor.vassistant",
            "com.hihonor.hiai",
            "com.heytap.speechassist",
            "com.coloros.speechassist",
            "com.oplus.ovoicemanager",
            "com.vivo.aispeech",
            "com.vivo.agent",
            "com.samsung.android.bixby.agent",
            "com.samsung.android.scs",
            "com.meizu.voiceassistant",
            "com.iflytek.speechsuite",
            "com.iflytek.vflynote",
            "com.baidu.input",
            "com.sohu.inputmethod.sogou",
            "com.google.android.tts",
            "com.google.android.googlequicksearchbox",
            "com.google.android.as"
        )

        private val KNOWN_EXACT_COMPONENTS = listOf(
            "com.xiaomi.mibrain.speech/.asr.AsrService",
            "com.miui.voiceassist/com.xiaomi.mibrain.speech.asr.AsrService"
        )

        private fun discoverCandidates(context: Context): List<SpeechCandidate> {
            val ordered = LinkedHashSet<SpeechCandidate>()

            // 1. Previously verified working candidate on this phone
            rememberedWorkingCandidate?.let { ordered.add(it) }

            // 2. System Secure setting `voice_recognition_service` (where Xiaomi/Huawei/OPPO/vivo store their active engine)
            val secureRaw = runCatching {
                Settings.Secure.getString(context.contentResolver, "voice_recognition_service")
            }.getOrNull()
            if (!secureRaw.isNullOrBlank()) {
                ComponentName.unflattenFromString(secureRaw.trim())?.let { comp ->
                    ordered.add(SpeechCandidate.ExplicitComponent(comp))
                }
            }

            // 3. Services advertising `android.speech.RecognitionService`
            val pm = context.packageManager
            val resolvedServices = runCatching {
                pm.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
            }.getOrDefault(emptyList())

            resolvedServices
                .sortedByDescending { it.serviceInfo?.exported == true && it.serviceInfo?.permission.isNullOrEmpty() }
                .forEach { info ->
                    val si = info.serviceInfo ?: return@forEach
                    ordered.add(SpeechCandidate.ExplicitComponent(ComponentName(si.packageName, si.name)))
                }

            // 4. Android 12+ on-device recognizer if reported available
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val onDeviceAvailable = runCatching {
                    SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
                }.getOrDefault(false)
                if (onDeviceAvailable) {
                    ordered.add(SpeechCandidate.OnDevice)
                }
            }

            // 5. Known OEM speech packages that omit standard RecognitionService intent-filters
            for (flat in KNOWN_EXACT_COMPONENTS) {
                val comp = ComponentName.unflattenFromString(flat) ?: continue
                val exists = runCatching { pm.getServiceInfo(comp, 0) != null }.getOrDefault(false)
                if (exists) {
                    ordered.add(SpeechCandidate.ExplicitComponent(comp))
                }
            }
            for (pkg in KNOWN_OEM_VOICE_PACKAGES) {
                val services = runCatching {
                    @Suppress("DEPRECATION")
                    pm.getPackageInfo(pkg, PackageManager.GET_SERVICES).services
                }.getOrNull() ?: continue
                for (svc in services) {
                    val simpleName = svc.name.substringAfterLast('.')
                    if (simpleName.contains("RecognitionService", ignoreCase = true) ||
                        simpleName.equals("AsrService", ignoreCase = true)
                    ) {
                        ordered.add(SpeechCandidate.ExplicitComponent(ComponentName(svc.packageName, svc.name)))
                    }
                }
            }

            // 6. Standard system default SpeechRecognizer
            ordered.add(SpeechCandidate.SystemDefault)

            return ordered.take(MAX_CANDIDATES)
        }

        fun hasRecognizerDialogActivity(context: Context): Boolean =
            runCatching {
                context.packageManager
                    .queryIntentActivities(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH), 0)
                    .isNotEmpty()
            }.getOrDefault(false)

        fun openSystemVoiceInputSettings(context: Context) {
            val candidates = listOf(
                Intent("android.settings.VOICE_INPUT_SETTINGS"),
                Intent(Settings.ACTION_INPUT_METHOD_SETTINGS),
                Intent(Settings.ACTION_SETTINGS)
            )
            for (intent in candidates) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (runCatching { context.startActivity(intent) }.isSuccess) {
                    return
                }
            }
        }

        fun buildRecognizerDialogIntent(): Intent =
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
                putExtra(RecognizerIntent.EXTRA_PROMPT, "请说出当前照片的配件与位置描述")
            }

        fun appendRecognizedSegment(existing: String, recognized: String): String {
            val cleanNew = recognized.trim().trimEnd('。', '，', '.', ',')
            if (cleanNew.isEmpty()) return existing
            val cleanExisting = existing.trim()
            if (cleanExisting.isEmpty()) return cleanNew
            val trailingPunct = setOf('，', '。', ',', '.', '、', '-', '_', '/')
            return if (cleanExisting.last() in trailingPunct) {
                "$cleanExisting$cleanNew"
            } else {
                "$cleanExisting $cleanNew"
            }
        }
    }
}
