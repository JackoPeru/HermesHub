package com.nemoclaw.chat.jarvis

/**
 * Autonomous Jarvis lifecycle, independent from the UI-facing JarvisPhase.
 *
 * The controller remains responsible for Meta Wearables DAT setup and cleanup;
 * this class only makes the long-lived session states and legal transitions
 * explicit.  Unknown/future gateway events must not be translated here unless
 * they have a declared lifecycle meaning.
 */
internal enum class JarvisLifecycleState {
    IDLE,
    STARTING,
    LISTENING,
    OBSERVING,
    PROCESSING,
    ESCALATING,
    SPEAKING,
    STOPPING,
    FAILED
}

internal enum class JarvisLifecycleEvent {
    START_REQUESTED,
    LISTENING_READY,
    FRAME_OBSERVED,
    PROCESSING_STARTED,
    OBSERVATION_COMPLETE,
    ESCALATION_REQUIRED,
    RESPONSE_READY,
    SPEECH_COMPLETE,
    STOP_REQUESTED,
    STOPPED,
    FAILURE,
    RESET
}

internal class JarvisLifecycleStateMachine(
    initialState: JarvisLifecycleState = JarvisLifecycleState.IDLE
) {
    @Volatile
    var state: JarvisLifecycleState = initialState
        private set

    @Synchronized
    fun transition(event: JarvisLifecycleEvent): JarvisLifecycleState {
        val next = transitionFor(state, event)
            ?: throw IllegalStateException("Illegal Jarvis lifecycle transition: $state + $event")
        state = next
        return next
    }

    @Synchronized
    fun tryTransition(event: JarvisLifecycleEvent): JarvisLifecycleState {
        return runCatching { transition(event) }.getOrDefault(state)
    }

    companion object {
        internal fun transitionFor(
            state: JarvisLifecycleState,
            event: JarvisLifecycleEvent
        ): JarvisLifecycleState? = when (state) {
            JarvisLifecycleState.IDLE -> when (event) {
                JarvisLifecycleEvent.START_REQUESTED -> JarvisLifecycleState.STARTING
                JarvisLifecycleEvent.STOP_REQUESTED -> JarvisLifecycleState.STOPPING
                JarvisLifecycleEvent.FAILURE -> JarvisLifecycleState.FAILED
                JarvisLifecycleEvent.RESET -> JarvisLifecycleState.IDLE
                else -> null
            }
            JarvisLifecycleState.STARTING -> when (event) {
                JarvisLifecycleEvent.LISTENING_READY -> JarvisLifecycleState.LISTENING
                JarvisLifecycleEvent.STOP_REQUESTED -> JarvisLifecycleState.STOPPING
                JarvisLifecycleEvent.FAILURE -> JarvisLifecycleState.FAILED
                else -> null
            }
            JarvisLifecycleState.LISTENING -> when (event) {
                JarvisLifecycleEvent.FRAME_OBSERVED -> JarvisLifecycleState.OBSERVING
                JarvisLifecycleEvent.PROCESSING_STARTED -> JarvisLifecycleState.PROCESSING
                JarvisLifecycleEvent.RESPONSE_READY -> JarvisLifecycleState.SPEAKING
                JarvisLifecycleEvent.STOP_REQUESTED -> JarvisLifecycleState.STOPPING
                JarvisLifecycleEvent.FAILURE -> JarvisLifecycleState.FAILED
                else -> null
            }
            JarvisLifecycleState.OBSERVING -> when (event) {
                JarvisLifecycleEvent.PROCESSING_STARTED -> JarvisLifecycleState.PROCESSING
                JarvisLifecycleEvent.OBSERVATION_COMPLETE -> JarvisLifecycleState.LISTENING
                JarvisLifecycleEvent.STOP_REQUESTED -> JarvisLifecycleState.STOPPING
                JarvisLifecycleEvent.FAILURE -> JarvisLifecycleState.FAILED
                else -> null
            }
            JarvisLifecycleState.PROCESSING -> when (event) {
                JarvisLifecycleEvent.OBSERVATION_COMPLETE -> JarvisLifecycleState.LISTENING
                JarvisLifecycleEvent.ESCALATION_REQUIRED -> JarvisLifecycleState.ESCALATING
                JarvisLifecycleEvent.RESPONSE_READY -> JarvisLifecycleState.SPEAKING
                JarvisLifecycleEvent.STOP_REQUESTED -> JarvisLifecycleState.STOPPING
                JarvisLifecycleEvent.FAILURE -> JarvisLifecycleState.FAILED
                else -> null
            }
            JarvisLifecycleState.ESCALATING -> when (event) {
                JarvisLifecycleEvent.RESPONSE_READY -> JarvisLifecycleState.SPEAKING
                JarvisLifecycleEvent.PROCESSING_STARTED -> JarvisLifecycleState.PROCESSING
                JarvisLifecycleEvent.STOP_REQUESTED -> JarvisLifecycleState.STOPPING
                JarvisLifecycleEvent.FAILURE -> JarvisLifecycleState.FAILED
                else -> null
            }
            JarvisLifecycleState.SPEAKING -> when (event) {
                JarvisLifecycleEvent.SPEECH_COMPLETE -> JarvisLifecycleState.LISTENING
                JarvisLifecycleEvent.STOP_REQUESTED -> JarvisLifecycleState.STOPPING
                JarvisLifecycleEvent.FAILURE -> JarvisLifecycleState.FAILED
                else -> null
            }
            JarvisLifecycleState.STOPPING -> when (event) {
                JarvisLifecycleEvent.STOPPED -> JarvisLifecycleState.IDLE
                JarvisLifecycleEvent.FAILURE -> JarvisLifecycleState.FAILED
                else -> null
            }
            JarvisLifecycleState.FAILED -> when (event) {
                JarvisLifecycleEvent.STOP_REQUESTED -> JarvisLifecycleState.STOPPING
                JarvisLifecycleEvent.START_REQUESTED -> JarvisLifecycleState.STARTING
                JarvisLifecycleEvent.RESET -> JarvisLifecycleState.IDLE
                JarvisLifecycleEvent.FAILURE -> JarvisLifecycleState.FAILED
                else -> null
            }
        }
    }
}
