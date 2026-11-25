package com.stamper.app.speech

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Process-wide holder for the bundled Alibaba FunASR SenseVoiceSmall (int8) model running on
 * sherpa-onnx. Works fully offline and doesn't depend on any OEM/Google speech service.
 */
object OfflineAsr {
    const val SAMPLE_RATE = 16000
    private const val TAG = "OfflineAsr"
    private const val MODEL_DIR = "asr/sensevoice"
    private const val MODEL_FILE = "$MODEL_DIR/model.int8.onnx"
    private const val TOKENS_FILE = "$MODEL_DIR/tokens.txt"

    @Volatile
    private var recognizer: OfflineRecognizer? = null
    private val loadLock = Any()
    private val decodeLock = Any()

    fun isBundled(context: Context): Boolean = runCatching {
        context.assets.list(MODEL_DIR)?.contains("model.int8.onnx") == true
    }.getOrDefault(false)

    fun isLoaded(): Boolean = recognizer != null

    fun preloadAsync(context: Context) {
        val appContext = context.applicationContext
        if (recognizer != null || !isBundled(appContext)) return
        thread(name = "asr-preload", isDaemon = true) {
            runCatching { getOrLoad(appContext) }
                .onFailure { Log.w(TAG, "preload failed", it) }
        }
    }

    private fun getOrLoad(context: Context): OfflineRecognizer {
        recognizer?.let { return it }
        synchronized(loadLock) {
            recognizer?.let { return it }
            val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
            val config = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                modelConfig = OfflineModelConfig(
                    senseVoice = OfflineSenseVoiceModelConfig(
                        model = MODEL_FILE,
                        language = "zh",
                        useInverseTextNormalization = true
                    ),
                    tokens = TOKENS_FILE,
                    numThreads = threads,
                    debug = false,
                    provider = "cpu"
                )
            )
            return OfflineRecognizer(assetManager = context.applicationContext.assets, config = config)
                .also { recognizer = it }
        }
    }

    fun transcribe(context: Context, samples: FloatArray): String {
        if (samples.isEmpty()) return ""
        val rec = getOrLoad(context)
        synchronized(decodeLock) {
            val stream = rec.createStream()
            try {
                stream.acceptWaveform(samples, SAMPLE_RATE)
                rec.decode(stream)
                return cleanTranscript(rec.getResult(stream).text)
            } finally {
                stream.release()
            }
        }
    }

    /** Drops trailing sentence punctuation the model adds so notes stay filename-friendly. */
    fun cleanTranscript(raw: String): String =
        raw.trim().trimEnd('。', '，', '.', ',', '？', '?', '！', '!', '、', ' ')
}

/**
 * Records 16 kHz mono PCM, shows live partial transcripts, auto-stops after a short pause and
 * delivers the final text via [onFinalResult]. Same contract as [SystemSpeechHelper].
 */
class OfflineSpeechHelper(
    context: Context,
    private val onFinalResult: (String) -> Unit
) : SpeechEngine {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val decodeExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "asr-decode").apply { isDaemon = true }
    }

    private val _state = MutableStateFlow(SpeechUiState(isAvailable = true))
    override val state: StateFlow<SpeechUiState> = _state.asStateFlow()

    @Volatile
    private var session: Session? = null
    @Volatile
    private var released = false

    override fun startListening() {
        if (released) return
        session?.cancel()

        val hasMic = ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (!hasMic) {
            _state.update {
                it.copy(isListening = false, errorMessage = "需要麦克风权限才能语音录入，请在系统设置中允许")
            }
            return
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
        Session().also { session = it }.start()
    }

    override fun stopListening() {
        session?.finishRequested?.set(true)
    }

    override fun release() {
        released = true
        session?.cancel()
        session = null
        decodeExecutor.shutdown()
        _state.update { it.copy(isListening = false, partialTranscript = "", rmsDb = 0f) }
    }

    private inner class Session {
        val finishRequested = AtomicBoolean(false)
        private val cancelled = AtomicBoolean(false)
        private val partialBusy = AtomicBoolean(false)
        private var buffer = FloatArray(SAMPLE_RATE * 8)
        private var length = 0

        fun cancel() {
            cancelled.set(true)
            finishRequested.set(true)
        }

        private fun isCurrent() = session === this && !cancelled.get() && !released

        fun start() {
            thread(name = "asr-record", isDaemon = true) { runRecording() }
        }

        @SuppressLint("MissingPermission")
        private fun runRecording() {
            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val record = runCatching {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minBuf, CHUNK_SAMPLES * 4)
                )
            }.getOrNull()

            if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
                record?.release()
                postError("麦克风被占用或无法打开，请关闭其他录音应用后重试")
                return
            }

            val chunk = ShortArray(CHUNK_SAMPLES)
            var speechStarted = false
            var trailingSilenceMs = 0
            var elapsedMs = 0
            var lastPartialAt = 0
            var noiseFloorDb = -60f

            try {
                record.startRecording()
                if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    postError("麦克风启动失败，可能被系统或其他应用占用")
                    return
                }

                while (!finishRequested.get()) {
                    val read = record.read(chunk, 0, chunk.size)
                    if (read <= 0) continue
                    append(chunk, read)
                    val chunkMs = read * 1000 / SAMPLE_RATE
                    elapsedMs += chunkMs

                    val db = chunkDbfs(chunk, read)
                    if (!speechStarted) noiseFloorDb = 0.9f * noiseFloorDb + 0.1f * db.coerceAtMost(-30f)
                    val isVoice = db > maxOf(VOICE_MIN_DBFS, noiseFloorDb + 10f)
                    if (isVoice) {
                        speechStarted = true
                        trailingSilenceMs = 0
                    } else if (speechStarted) {
                        trailingSilenceMs += chunkMs
                    }
                    val uiLevel = ((db + 55f) / 45f * 12f).coerceIn(0f, 12f)
                    _state.update { it.copy(rmsDb = uiLevel) }

                    if (speechStarted && elapsedMs - lastPartialAt >= PARTIAL_INTERVAL_MS) {
                        lastPartialAt = elapsedMs
                        schedulePartial()
                    }

                    when {
                        speechStarted && trailingSilenceMs >= END_SILENCE_MS -> break
                        !speechStarted && elapsedMs >= NO_SPEECH_TIMEOUT_MS -> {
                            postError("没有听到声音，请靠近手机再点麦克风说一次")
                            return
                        }
                        elapsedMs >= MAX_UTTERANCE_MS -> break
                    }
                }
            } catch (t: Throwable) {
                postError("录音异常：${t.localizedMessage ?: t.javaClass.simpleName}")
                return
            } finally {
                runCatching { record.stop() }
                record.release()
            }

            if (cancelled.get()) return
            _state.update { it.copy(isListening = false, rmsDb = 0f) }
            val samples = snapshot()
            if (samples.isEmpty()) {
                _state.update { it.copy(partialTranscript = "") }
                return
            }
            decodeAndDeliverFinal(samples)
        }

        private fun schedulePartial() {
            if (!partialBusy.compareAndSet(false, true)) return
            val samples = snapshot()
            runCatching {
                decodeExecutor.execute {
                    try {
                        if (!isCurrent()) return@execute
                        val text = OfflineAsr.transcribe(appContext, samples)
                        if (isCurrent() && _state.value.isListening) {
                            _state.update { it.copy(partialTranscript = text) }
                        }
                    } catch (t: Throwable) {
                        Log.w("OfflineSpeechHelper", "partial decode failed", t)
                    } finally {
                        partialBusy.set(false)
                    }
                }
            }.onFailure { partialBusy.set(false) }
        }

        private fun decodeAndDeliverFinal(samples: FloatArray) {
            runCatching {
                decodeExecutor.execute {
                    val result = runCatching { OfflineAsr.transcribe(appContext, samples) }
                    mainHandler.post {
                        if (!isCurrent()) return@post
                        result.onSuccess { text ->
                            _state.update { it.copy(partialTranscript = "", errorMessage = null) }
                            if (text.isNotBlank()) {
                                onFinalResult(text)
                            } else {
                                _state.update { it.copy(errorMessage = "没听清，请再说一次") }
                            }
                        }.onFailure { err ->
                            _state.update {
                                it.copy(
                                    partialTranscript = "",
                                    errorMessage = "离线识别失败：${err.localizedMessage ?: err.javaClass.simpleName}"
                                )
                            }
                        }
                    }
                }
            }
        }

        private fun postError(message: String) {
            if (cancelled.get()) return
            _state.update {
                it.copy(isListening = false, rmsDb = 0f, partialTranscript = "", errorMessage = message)
            }
        }

        @Synchronized
        private fun append(chunk: ShortArray, count: Int) {
            if (length + count > buffer.size) {
                buffer = buffer.copyOf(maxOf(buffer.size * 2, length + count))
            }
            for (i in 0 until count) {
                buffer[length + i] = chunk[i] / 32768f
            }
            length += count
        }

        @Synchronized
        private fun snapshot(): FloatArray = buffer.copyOf(length)
    }

    companion object {
        private const val SAMPLE_RATE = OfflineAsr.SAMPLE_RATE
        private const val CHUNK_SAMPLES = SAMPLE_RATE / 10
        private const val PARTIAL_INTERVAL_MS = 700
        private const val END_SILENCE_MS = 1600
        private const val NO_SPEECH_TIMEOUT_MS = 8000
        private const val MAX_UTTERANCE_MS = 30000
        private const val VOICE_MIN_DBFS = -42f

        internal fun chunkDbfs(chunk: ShortArray, count: Int): Float {
            if (count <= 0) return -90f
            var sum = 0.0
            for (i in 0 until count) {
                val v = chunk[i] / 32768.0
                sum += v * v
            }
            val rms = sqrt(sum / count)
            return if (rms <= 1e-9) -90f else (20 * log10(rms)).toFloat()
        }
    }
}
