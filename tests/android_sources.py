"""Helper condivisi per i contract test: sorgenti Android dopo lo split dei god file.

Ex god file spezzati (stesso package, solo file diversi):
- ChatStream.kt -> com/nemoclaw/chat/Stream*.kt
- core/HubOperations.kt -> core/Hub*.kt
- features/bots/BotsFeature.kt -> features/bots/Bots*.kt
- features/server/ServerFeature.kt -> features/server/Server*.kt
"""

from __future__ import annotations

import pathlib


ROOT = pathlib.Path(__file__).resolve().parents[1]
ANDROID_JAVA = (
    ROOT / "src" / "NemoclawChat.Android" / "app" / "src" / "main" / "java"
    / "com" / "nemoclaw" / "chat"
)


def _concat(directory: pathlib.Path, pattern: str) -> str:
    return "\n".join(
        path.read_text(encoding="utf-8") for path in sorted(directory.glob(pattern))
    )


def read_android_stream() -> str:
    """Ex ChatStream.kt."""
    return _concat(ANDROID_JAVA, "Stream*.kt")


def read_android_hub_ops() -> str:
    """Ex core/HubOperations.kt."""
    return _concat(ANDROID_JAVA / "core", "Hub*.kt")


def read_android_bots_ui() -> str:
    """Ex features/bots/BotsFeature.kt."""
    return _concat(ANDROID_JAVA / "features" / "bots", "Bots*.kt")


def read_android_server_ui() -> str:
    """Ex features/server/ServerFeature.kt."""
    return _concat(ANDROID_JAVA / "features" / "server", "Server*.kt")


def read_android_chat_ui() -> str:
    """UI chat (ChatFeature spezzato in Chat*.kt, stesso package)."""
    return _concat(ANDROID_JAVA / "features" / "chat", "Chat*.kt")
