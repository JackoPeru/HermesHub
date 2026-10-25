package com.nemoclaw.chat

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.nemoclaw.chat.ui.theme.ChatClawTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Screenshot della home vera su JVM (Robolectric, niente emulatore).
 * Viewport da telefono alto, tema dark reale + capture Roborazzi su file.
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
            ChatClawTheme {
                EmptyState(
                    onPrompt = {},
                    serverOnline = true,
                    onOpenSidebar = {}
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onRoot().captureRoboImage(
            file = File("C:/Users/matte/AppData/Local/Temp/opencode/robo_home.png")
        )
    }

    @Test
    fun composerEmpty() {
        composeRule.setContent {
            ChatClawTheme {
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
        }
        composeRule.waitForIdle()
        composeRule.onRoot().captureRoboImage(
            file = File("C:/Users/matte/AppData/Local/Temp/opencode/robo_composer.png")
        )
    }

    @Test
    fun heroBotMenuOpen() {
        composeRule.setContent {
            ChatClawTheme {
                EmptyState(
                    onPrompt = {},
                    serverOnline = true,
                    onOpenSidebar = {},
                    identityPicker = ChatIdentityPicker(
                        currentLabel = "Hermes",
                        activeKey = "",
                        loading = false,
                        status = "",
                        options = listOf(ChatIdentityOption("conn::socrate", "Socrate", "primary")),
                        onPick = {},
                        onRefresh = {}
                    )
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Hermes Hub").performClick()
        composeRule.waitForIdle()
        captureScreenRoboImage(
            file = File("C:/Users/matte/AppData/Local/Temp/opencode/robo_botmenu.png")
        )
    }

    @Test
    fun heroAvatarMenuOpen() {
        composeRule.setContent {
            ChatClawTheme {
                EmptyState(
                    onPrompt = {},
                    serverOnline = true,
                    onOpenSidebar = {},
                    contextUsage = ContextUsage(tokens = 32000, percent = 24),
                    settings = AppSettings(),
                    managerApiKey = null,
                    onNewChat = {},
                    onOpenComfy = {}
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Menu rapido: contesto 24% e manager").performClick()
        composeRule.waitForIdle()
        captureScreenRoboImage(
            file = java.io.File("C:/Users/matte/AppData/Local/Temp/opencode/robo_avatarmenu.png")
        )
    }

    @Test
    fun heroBotPickDismissesAndPicks() {
        var picked = "none"
        composeRule.setContent {
            ChatClawTheme {
                EmptyState(
                    onPrompt = {},
                    serverOnline = true,
                    onOpenSidebar = {},
                    identityPicker = ChatIdentityPicker(
                        currentLabel = "Hermes",
                        activeKey = "",
                        loading = false,
                        status = "",
                        options = listOf(ChatIdentityOption("conn::socrate", "Socrate", "primary")),
                        onPick = { picked = it },
                        onRefresh = {}
                    )
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Hermes Hub").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Socrate").performClick()
        composeRule.waitForIdle()
        org.junit.Assert.assertEquals("conn::socrate", picked)
        composeRule.onRoot().captureRoboImage(
            file = java.io.File("C:/Users/matte/AppData/Local/Temp/opencode/robo_botpick.png")
        )
    }
}
