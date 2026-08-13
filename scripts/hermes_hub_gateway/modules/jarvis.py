"""Jarvis lifecycle and bounded in-memory work queues.

This is a Hub-owned coordination boundary.  Hermes Agent remains responsible
for agent planning, durable memory, tools, policy, and retries.  Frame bytes
never leave this bounded RAM-only buffer and are discarded during cleanup.
"""

from __future__ import annotations

from collections import deque
from dataclasses import dataclass
from enum import Enum
import threading
from typing import Deque


class JarvisPhase(str, Enum):
    IDLE = "idle"
    STARTING = "starting"
    ACTIVE = "active"
    STOPPING = "stopping"
    FAILED = "failed"


@dataclass(frozen=True, slots=True)
class JarvisFrame:
    frame_id: str
    data: bytes


class SingleModelGpuGate:
    """A process-local semaphore shared by observer and escalation callers."""

    def __init__(self) -> None:
        self._semaphore = threading.BoundedSemaphore(1)

    def acquire(self, timeout: float | None = None) -> bool:
        return self._semaphore.acquire() if timeout is None else self._semaphore.acquire(timeout=timeout)

    def release(self) -> None:
        self._semaphore.release()

    def __enter__(self) -> "SingleModelGpuGate":
        if not self.acquire():
            raise RuntimeError("Jarvis model gate could not be acquired")
        return self

    def __exit__(self, _exc_type: object, _exc: object, _traceback: object) -> None:
        self.release()


class JarvisLifecycle:
    """Explicit lifecycle with one worker and latest-frame backpressure."""

    CLEANUP_ORDER = ("frames", "stream", "session", "worker", "gateway")

    def __init__(self, *, max_frame_bytes: int = 4 * 1024 * 1024, max_voice_requests: int = 8) -> None:
        self.max_frame_bytes = max(1, int(max_frame_bytes))
        self.max_voice_requests = max(1, int(max_voice_requests))
        self.phase = JarvisPhase.IDLE
        self.worker_started = False
        self.last_error: str | None = None
        self.cleanup_log: list[str] = []
        self.active_frame: JarvisFrame | None = None
        self.pending_frame: JarvisFrame | None = None
        self._voice_requests: Deque[str] = deque()

    def start(self) -> None:
        if self.phase is not JarvisPhase.IDLE:
            raise RuntimeError(f"Jarvis cannot start from {self.phase.value}")
        self.cleanup_log.clear()
        self.last_error = None
        self.worker_started = True
        self.phase = JarvisPhase.STARTING

    def mark_streaming(self) -> None:
        if self.phase is not JarvisPhase.STARTING:
            raise RuntimeError("Jarvis stream can start only during startup")
        self.phase = JarvisPhase.ACTIVE

    def submit_frame(self, frame_id: str, data: bytes | bytearray | memoryview) -> bool:
        if self.phase is not JarvisPhase.ACTIVE:
            return False
        frame = bytes(data)
        if not frame or len(frame) > self.max_frame_bytes:
            return False
        value = JarvisFrame(str(frame_id), frame)
        if self.active_frame is None:
            self.active_frame = value
        else:
            # Keep only the newest pending observation while the worker
            # finishes the active frame.
            self.pending_frame = value
        return True

    def begin_frame(self) -> JarvisFrame | None:
        frame = self.active_frame
        self.active_frame = None
        return frame

    def complete_frame(self) -> None:
        if self.active_frame is None and self.pending_frame is not None:
            self.active_frame = self.pending_frame
            self.pending_frame = None

    def submit_voice_request(self, request: str) -> bool:
        if self.phase is not JarvisPhase.ACTIVE:
            return False
        value = str(request).strip()
        if not value or len(self._voice_requests) >= self.max_voice_requests:
            return False
        self._voice_requests.append(value)
        return True

    def next_voice_request(self) -> str | None:
        return self._voice_requests.popleft() if self._voice_requests else None

    def fail(self, reason: str) -> None:
        if self.phase is JarvisPhase.IDLE:
            self.last_error = str(reason)[:500]
            self.phase = JarvisPhase.FAILED
            return
        self.last_error = str(reason)[:500]
        self._cleanup()
        self.phase = JarvisPhase.FAILED

    def stop(self) -> None:
        if self.phase is JarvisPhase.IDLE:
            return
        self._cleanup()
        self.phase = JarvisPhase.IDLE
        self.last_error = None

    def cancel(self) -> None:
        self.stop()

    def _cleanup(self) -> None:
        self.phase = JarvisPhase.STOPPING
        for resource in self.CLEANUP_ORDER:
            if resource == "frames":
                self.active_frame = None
                self.pending_frame = None
                self._voice_requests.clear()
            elif resource == "worker":
                self.worker_started = False
            if resource not in self.cleanup_log:
                self.cleanup_log.append(resource)


__all__ = ["JarvisFrame", "JarvisLifecycle", "JarvisPhase", "SingleModelGpuGate"]
