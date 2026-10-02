package com.nemoclaw.chat

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Holds the voice-call session across configuration changes (rotation, etc.).
 *
 * The call coroutine runs in [viewModelScope], the waiting tone lives here, and
 * [MediaPlayer] playback is tracked by [VoiceTurnController] — none of them are
 * bound to the Activity, so rotating the phone no longer kills the call.
 * The session ends only via [endCall]/[shutdown] or ViewModel clearance.
 */
internal class VoiceCallViewModel(app: Application) : AndroidViewModel(app) {
    private val appContext: Context
        get() = getApplication<Application>().applicationContext

    var callActive by mutableStateOf(false)
        private set
    var phase by mutableStateOf(VoiceCallPhase.Idle)
        private set
    var status by mutableStateOf("Hermes voce pronto.")
        private set
    val history: SnapshotStateList<ChatMessage> = mutableStateListOf()

    private var callJob: Job? = null
    private var startInProgress = false
    private var startedBluetooth = false
    private var voiceConversation = VoiceConversationContext("voice_none")
    private val waitingTone = WaitingTonePlayer(appContext)

    private fun updatePhase(next: VoiceCallPhase) {
        phase = next
        if (next == VoiceCallPhase.Thinking) waitingTone.start() else waitingTone.stop()
    }

    fun startCall(
        settings: AppSettings,
        apiKey: String?,
        voice: String,
        speed: Double,
        bluetooth: Boolean
    ) {
        if (callActive || startInProgress) return
        startInProgress = true
        callJob?.cancel()
        callActive = true
        updatePhase(VoiceCallPhase.Connecting)
        status = "Connessione a Hermes..."
        if (bluetooth) {
            startedBluetooth = true
            routeVoiceBluetooth(appContext, true)
        }
        callJob = viewModelScope.launch {
            try {
                verifyVoiceGateway(settings, apiKey)
                if (!callActive) return@launch
                updatePhase(VoiceCallPhase.Listening)
                status = "Ti ascolto."
                history.clear()
                startVoiceForegroundService(appContext)
                voiceConversation = VoiceConversationContext(
                    "voice_${System.currentTimeMillis()}_${java.util.UUID.randomUUID()}"
                )
                runVoiceCallLoop(
                    context = appContext,
                    settings = settings,
                    apiKey = apiKey,
                    history = history,
                    voiceConversation = voiceConversation,
                    isCallActive = { callActive },
                    setPhase = ::updatePhase,
                    setStatus = { status = it },
                    voice = { voice },
                    speed = { speed }
                )
            } catch (_: CancellationException) {
            } catch (ex: Exception) {
                if (callActive) {
                    updatePhase(VoiceCallPhase.Error)
                    status = "Voce non disponibile: ${ex.message ?: "errore sconosciuto"}"
                    callActive = false
                    if (startedBluetooth) {
                        startedBluetooth = false
                        routeVoiceBluetooth(appContext, false)
                    }
                }
            } finally {
                startInProgress = false
            }
        }
    }

    fun interruptCall() {
        if (!callActive) return
        VoiceTurnController.interrupt()
        updatePhase(VoiceCallPhase.Listening)
        status = "Hermes interrotto. Ti ascolto."
    }

    fun endCall(settings: AppSettings, apiKey: String?) {
        if (!callActive && callJob == null) return
        callActive = false
        VoiceTurnController.interrupt()
        callJob?.cancel()
        callJob = null
        startInProgress = false
        stopVoiceForegroundService(appContext)
        if (startedBluetooth) {
            startedBluetooth = false
            routeVoiceBluetooth(appContext, false)
        }
        updatePhase(VoiceCallPhase.Idle)
        val snapshot = history.toList()
        viewModelScope.launch {
            status = saveVoiceCall(appContext, settings, apiKey, snapshot)
        }
    }

    /** Ends the session when the screen is really gone (not on rotation). */
    fun shutdown() {
        callActive = false
        callJob?.cancel()
        callJob = null
        startInProgress = false
        VoiceTurnController.interrupt()
        stopVoiceForegroundService(appContext)
        if (startedBluetooth) {
            startedBluetooth = false
            routeVoiceBluetooth(appContext, false)
        }
        waitingTone.stop()
    }

    override fun onCleared() {
        shutdown()
        waitingTone.release()
    }
}
