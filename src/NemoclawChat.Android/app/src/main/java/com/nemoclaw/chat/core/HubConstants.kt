package com.nemoclaw.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

internal const val VISUAL_BLOCKS_VERSION = 1
internal const val VISUAL_BLOCKS_MAX_PAYLOAD_BYTES = 500 * 1024
internal val ALLOWED_CODE_LANGUAGES = setOf(
    "plaintext",
    "mermaid",
    "powershell",
    "bash",
    "json",
    "xml",
    "csharp",
    "kotlin",
    "python",
    "javascript",
    "typescript",
    "sql",
    "yaml",
    "markdown"
)

internal const val CURRENT_WORKSPACE_PREFS = "chatclaw_workspace_requests"
internal const val SHOW_RAW_HERMES_EVENTS_IN_CHAT = false
internal const val CHAT_HISTORY_MAX_MESSAGES = 30
internal const val STREAMING_CHECKPOINT_INTERVAL_MS = 5000L
/** Finestra di fallback quando il server non dichiara context_length: 131072 = max_seq_len runtime di TabbyAPI/EXL3 (verificato in config.yml; n_ctx_train 262144 non è il limite operativo). */
internal const val DEFAULT_CONTEXT_WINDOW_TOKENS = 131072
internal const val CONTEXT_SYSTEM_OVERHEAD_TOKENS = 900
internal const val MESSAGE_CONTEXT_OVERHEAD_TOKENS = 6

