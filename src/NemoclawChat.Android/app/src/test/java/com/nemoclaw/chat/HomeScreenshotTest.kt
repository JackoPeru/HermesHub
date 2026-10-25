package com.nemoclaw.chat

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Screenshot della home vera su JVM (Robolectric, niente emulatore).
 * Viewport da telefono alto + capture Roborazzi su file esplicito.
 * PNG in C:/Users/matte/AppData/Local/Temp/opencode/robo_*.png
 */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
@RunWith(RobolectricTestRunner::class)
class HomeScreenshotTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<TestHarnessActivity>()

    @Test
    fun homeEmptyState() {
        composeRule.setContent {
            EmptyState(
                onPrompt = {},
                serverOnline = true,
                onOpenSidebar = {},
                onOpenProfile = {}
            )
        }
        composeRule.waitForIdle()
        composeRule.onRoot().captureRoboImage(
            file = File("C:/Users/matte/AppData/Local/Temp/opencode/robo_home.png")
        )
    }

    @Test
    fun composerEmpty() {
        composeRule.setContent {
            Composer(
                value = "",
                attachments = emptyList(),
                onValueChange = {},
                onAttachImage = {},
                onTakePhoto = {},
                onScanDocument = {},
                onRemoveAttachment = {},
                reasoningEffort = "",
                reasoningOptions = emptyList(),
                onReasoningChange = {},
                quickPrompt = null,
                onQuickPromptConsumed = {},
                onSend = {},
                onStop = {},
                isBusy = false,
                isRecordingVoiceNote = false,
                onToggleVoiceNote = {}
            )
        }
        composeRule.waitForIdle()
        composeRule.onRoot().captureRoboImage(
            file = File("C:/Users/matte/AppData/Local/Temp/opencode/robo_composer.png")
        )
    }
}
