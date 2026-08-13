package com.nemoclaw.chat.jarvis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class JarvisLifecycleStateMachineTest {
    @Test
    fun `normal autonomous session follows listening observing processing escalation and speech`() {
        val machine = JarvisLifecycleStateMachine()

        assertEquals(JarvisLifecycleState.STARTING, machine.transition(JarvisLifecycleEvent.START_REQUESTED))
        assertEquals(JarvisLifecycleState.LISTENING, machine.transition(JarvisLifecycleEvent.LISTENING_READY))
        assertEquals(JarvisLifecycleState.OBSERVING, machine.transition(JarvisLifecycleEvent.FRAME_OBSERVED))
        assertEquals(JarvisLifecycleState.PROCESSING, machine.transition(JarvisLifecycleEvent.PROCESSING_STARTED))
        assertEquals(JarvisLifecycleState.ESCALATING, machine.transition(JarvisLifecycleEvent.ESCALATION_REQUIRED))
        assertEquals(JarvisLifecycleState.SPEAKING, machine.transition(JarvisLifecycleEvent.RESPONSE_READY))
        assertEquals(JarvisLifecycleState.LISTENING, machine.transition(JarvisLifecycleEvent.SPEECH_COMPLETE))
    }

    @Test
    fun `failure and deterministic cleanup return to idle`() {
        val machine = JarvisLifecycleStateMachine()

        machine.transition(JarvisLifecycleEvent.START_REQUESTED)
        assertEquals(JarvisLifecycleState.FAILED, machine.transition(JarvisLifecycleEvent.FAILURE))
        assertEquals(JarvisLifecycleState.STOPPING, machine.transition(JarvisLifecycleEvent.STOP_REQUESTED))
        assertEquals(JarvisLifecycleState.IDLE, machine.transition(JarvisLifecycleEvent.STOPPED))
    }

    @Test
    fun `illegal transition is rejected while try transition is fail closed`() {
        val machine = JarvisLifecycleStateMachine()

        assertThrows(IllegalStateException::class.java) {
            machine.transition(JarvisLifecycleEvent.RESPONSE_READY)
        }
        assertEquals(
            JarvisLifecycleState.IDLE,
            machine.tryTransition(JarvisLifecycleEvent.RESPONSE_READY)
        )
    }
}
