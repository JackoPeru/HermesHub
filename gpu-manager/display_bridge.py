"""Bot-desktop display bridge for the Hermes GPU manager.

Exposes the official per-profile Xvnc desktop (started via
``hermes computer-use screen start``) to HermesHub over the manager API:

- GET  /display/status      running/geometry/holder (Bearer)
- POST /display/ticket      single-use 30 s viewer ticket (Bearer)
- WS   /display/ws?ticket=  RFB bridge for noVNC viewers (ticket auth)
- POST /display/takeover    human takes control (lease acquire, Bearer)
- POST /display/release     hand control back to the bot (Bearer)
- GET  /display/frame.png   PNG snapshot for previews (Bearer)

Control semantics mirror the official stack: no lease file means the bot
holds control; input bytes are forwarded only while our viewer holds a HUMAN
lease; a takeover by someone else closes our socket (control-taken) so the UI
drops back to view-only. Unknown RFB bytes are never invented: client message
framing follows RFB 3.8 §6.4, server bytes are pure passthrough.
"""

from __future__ import annotations

import asyncio
import hmac
import io
import logging
import os
import secrets
import socket
import struct
import sys
import threading
import time
from typing import Any, Dict, Optional, Tuple

LOG = logging.getLogger("hermes.display_bridge")

try:
    from fastapi import APIRouter, Depends, HTTPException, Request, WebSocket, WebSocketDisconnect
    from fastapi.responses import Response
    _FASTAPI = True
except Exception:  # pragma: no cover - import-time only
    APIRouter = Depends = HTTPException = Request = WebSocket = WebSocketDisconnect = None  # type: ignore
    Response = None  # type: ignore
    _FASTAPI = False

try:
    from PIL import Image
    _PIL = True
except Exception:
    _PIL = False

router = APIRouter() if _FASTAPI else None

_AGENT_ROOT_CANDIDATES = (
    os.path.join(os.path.expanduser("~"), ".hermes", "hermes-agent"),
)


def _ensure_agent_path() -> Optional[str]:
    override = os.environ.get("HERMES_AGENT_ROOT", "").strip()
    candidates = ([override] if override else []) + list(_AGENT_ROOT_CANDIDATES)
    for candidate in candidates:
        if candidate and os.path.isdir(candidate) and candidate not in sys.path:
            sys.path.insert(0, candidate)
            return candidate
    for candidate in candidates:
        if candidate and os.path.isdir(candidate):
            return candidate
    return None


def _agent_root() -> Optional[str]:
    return _ensure_agent_path()


def _manager_config() -> Dict[str, Any]:
    """Read the running manager's CONFIG without re-importing the module.

    manager.py executes as ``__main__``; a plain ``from manager import ...``
    would execute the file a second time (workers, sockets). The already
    imported ``__main__`` module object is the single source of truth.
    """
    main = sys.modules.get("__main__")
    config = getattr(main, "CONFIG", None)
    return dict(config) if isinstance(config, dict) else {}


def _lease_module():
    """Import the official lease backend (file locking + Desktop-compatible format)."""
    _ensure_agent_path()
    from tools.bot_desktop import lease as lease_mod
    return lease_mod


def _hermes_home() -> str:
    try:
        from hermes_cli.config import get_hermes_home  # type: ignore
        return str(get_hermes_home())
    except Exception:
        return os.path.expanduser("~/.hermes")


def _bot_desktop_dir() -> str:
    return os.path.join(_hermes_home(), "bot-desktop")


def _rfb_socket_path() -> str:
    return os.path.join(_bot_desktop_dir(), "rfb.sock")


def _touch_official_activity() -> None:
    """Tell the official watcher this screen is being watched.

    The gateway stops screens idle past bot_desktop.idle_stop_minutes based
    on ITS activity file; our external viewers (previews, WS) must count as
    usage or a watched-only screen gets reaped. Best-effort, never raises.
    """
    try:
        _ensure_agent_path()
        from tools.bot_desktop import runtime as _runtime
        touch = getattr(_runtime, "touch_activity", None)
        if callable(touch):
            touch()
    except Exception:
        pass


def _lease_state() -> Dict[str, Any]:
    """Read control state. Never raises; unknown means bot (fresh profile)."""
    try:
        lease_mod = _lease_module()
        lease = lease_mod.get()
        return {
            "holder": str(getattr(lease, "holder", "bot") or "bot"),
            "viewer_id": str(getattr(lease, "viewer_id", "") or ""),
            "since": float(getattr(lease, "since", 0.0) or 0.0),
            "reason": str(getattr(lease, "reason", "") or ""),
        }
    except Exception as exc:
        LOG.debug("lease read failed: %r", exc)
        return {"holder": "bot", "viewer_id": "", "since": 0.0, "reason": ""}


# --------------------------------------------------------------------------
# Minimal RFB 3.8 client (snapshot path). Server bytes for the WS bridge are
# pure passthrough; only client->server framing is parsed (input gating).
# --------------------------------------------------------------------------

_RFB_VERSION = b"RFB 003.008\n"
_FB_UPDATE_REQUEST = struct.Struct("!BBHHHH")


def _recv_exact(sock: socket.socket, count: int, timeout: float = 10.0) -> bytes:
    sock.settimeout(timeout)
    chunks = []
    remaining = count
    while remaining > 0:
        chunk = sock.recv(remaining)
        if not chunk:
            raise ConnectionError("rfb socket closed")
        chunks.append(chunk)
        remaining -= len(chunk)
    return b"".join(chunks)


def _rbf_handshake(sock: socket.socket) -> Tuple[int, int, Dict[str, Any]]:
    server_version = _recv_exact(sock, 12)
    if not server_version.startswith(b"RFB 003."):
        raise ConnectionError(f"unexpected RFB version: {server_version!r}")
    sock.sendall(_RFB_VERSION)
    n_auth = struct.unpack("!B", _recv_exact(sock, 1))[0]
    if n_auth == 0:
        reason_len = struct.unpack("!I", _recv_exact(sock, 4))[0]
        reason = _recv_exact(sock, reason_len).decode("utf-8", "replace")
        raise ConnectionError(f"rfb auth refused: {reason}")
    auth_types = _recv_exact(sock, n_auth)
    if 1 not in auth_types:
        raise ConnectionError(f"rfb needs auth, server offers: {list(auth_types)}")
    sock.sendall(b"\x01")
    auth_result = struct.unpack("!I", _recv_exact(sock, 4))[0]
    if auth_result != 0:
        raise ConnectionError(f"rfb auth failed: {auth_result}")
    sock.sendall(b"\x01")  # shared desktop
    init = _recv_exact(sock, 24)
    width, height = struct.unpack("!HH", init[0:4])
    pixfmt = init[4:20]
    bpp, depth = pixfmt[0], pixfmt[1]
    name_len = struct.unpack("!I", init[20:24])[0]
    name = _recv_exact(sock, name_len).decode("utf-8", "replace") if name_len else ""
    return width, height, {"bpp": bpp, "depth": depth, "pixfmt": pixfmt, "name": name}


def _rbf_request_frame(sock: socket.socket, width: int, height: int) -> None:
    sock.sendall(struct.pack("!BBH", 2, 0, 1) + struct.pack("!i", 0))  # SetEncodings: RAW only
    sock.sendall(_FB_UPDATE_REQUEST.pack(3, 0, 0, 0, width, height))


def rfb_snapshot_png(sock_path: Optional[str] = None, max_width: int = 960) -> Tuple[bytes, int, int]:
    """Grab one full frame as PNG. Raises on any failure (caller maps to HTTP)."""
    if not _PIL:
        raise RuntimeError("Pillow unavailable in manager venv")
    path = sock_path or _rfb_socket_path()
    sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    try:
        sock.connect(path)
        width, height, info = _rbf_handshake(sock)
        if info["bpp"] != 32:
            raise ConnectionError(f"unsupported rfb pixel depth: {info}")
        _rbf_request_frame(sock, width, height)
        # Skip FramebufferUpdate header (4B) then rectangles; accumulate RAW pixels.
        header = _recv_exact(sock, 4)
        if header[0] != 0:
            raise ConnectionError(f"unexpected rfb server message: {header[0]}")
        (n_rects,) = struct.unpack("!H", header[2:4])
        canvas = bytearray(width * height * 4)
        for _ in range(n_rects):
            rect = _recv_exact(sock, 12)
            x, y, w, h, enc = struct.unpack("!HHHHi", rect)
            if enc != 0:
                raise ConnectionError(f"server sent non-RAW encoding {enc}; refusing partial frame")
            raw = _recv_exact(sock, w * h * 4, timeout=30.0)
            bpp_row = w * 4
            for row in range(h):
                dst = ((y + row) * width + x) * 4
                src = row * bpp_row
                canvas[dst:dst + bpp_row] = raw[src:src + bpp_row]
        image = Image.frombytes("RGBA", (width, height), bytes(canvas))
        if max_width > 0 and width > max_width:
            ratio = max_width / float(width)
            image = image.resize((max_width, max(1, int(height * ratio))), Image.BILINEAR)
            width, height = image.size
        buffer = io.BytesIO()
        image.convert("RGB").save(buffer, format="PNG")
        return buffer.getvalue(), width, height
    finally:
        try:
            sock.close()
        except Exception:
            pass


# --------------------------------------------------------------------------
# Client->server framing (RFB 3.8 §6.4) for the input gate.
# Returns (consumed, is_input, complete, fatal). Unknown or absurd message
# shapes are fatal: the connection closes instead of desyncing the stream.
# --------------------------------------------------------------------------

_CLIENT_FIXED = {0: 20, 4: 8, 5: 6}  # SetPixelFormat, KeyEvent, PointerEvent
_MAX_PENDING = 1_048_576 + 8


def parse_client_message(buffer: bytes) -> Tuple[int, bool, bool, bool]:
    """Parse one client message prefix. (consumed, is_input, complete, fatal)."""
    if len(buffer) < 1:
        return 0, False, False, False
    kind = buffer[0]
    if kind in _CLIENT_FIXED:
        need = _CLIENT_FIXED[kind]
        if len(buffer) < need:
            return 0, kind in (4, 5), False, False
        return need, kind in (4, 5), True, False
    if kind == 2:  # SetEncodings: 1 + 1 + 2 + 4*n
        if len(buffer) < 4:
            return 0, False, False, False
        count = struct.unpack("!H", buffer[2:4])[0]
        if count > 64:
            return 0, False, True, True
        need = 4 + 4 * count
        if len(buffer) < need:
            return 0, False, False, False
        return need, False, True, False
    if kind == 3:  # FramebufferUpdateRequest: 10 bytes, not input
        if len(buffer) < 10:
            return 0, False, False, False
        return 10, False, True, False
    if kind == 6:  # ClientCutText: 8 + length
        if len(buffer) < 8:
            return 0, False, False, False
        length = struct.unpack("!i", buffer[4:8])[0]
        if length < 0 or length > 1_048_576:
            return 0, True, True, True
        need = 8 + length
        if len(buffer) < need:
            return 0, True, False, False
        return need, True, True, False
    return 0, True, True, True


# --------------------------------------------------------------------------
# Tickets (single-use 30 s, memory only).
# --------------------------------------------------------------------------

_tickets: Dict[str, Tuple[str, float]] = {}
_tickets_lock = threading.Lock()
_TICKET_TTL_S = 30.0


def mint_ticket() -> Tuple[str, str, float]:
    viewer_id = f"hermeshub-{secrets.token_hex(4)}"
    ticket = secrets.token_urlsafe(24)
    with _tickets_lock:
        now = time.time()
        for stale in [key for key, (_, exp) in _tickets.items() if exp <= now]:
            _tickets.pop(stale, None)
        _tickets[ticket] = (viewer_id, now + _TICKET_TTL_S)
    return ticket, viewer_id, _TICKET_TTL_S


def consume_ticket(ticket: str) -> Optional[str]:
    with _tickets_lock:
        record = _tickets.pop(ticket, None)
    if record is None:
        return None
    viewer_id, expires = record
    if time.time() > expires:
        return None
    return viewer_id


# --------------------------------------------------------------------------
# FastAPI surface (registered by manager.py; no-ops without fastapi).
# --------------------------------------------------------------------------

def rfb_geometry(sock_path: Optional[str] = None) -> Tuple[int, int]:
    """Read display geometry via handshake only (no framebuffer transfer)."""
    path = sock_path or _rfb_socket_path()
    sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    try:
        sock.connect(path)
        width, height, _info = _rbf_handshake(sock)
        return width, height
    finally:
        try:
            sock.close()
        except Exception:
            pass


if _FASTAPI:
    router = APIRouter()

    def _require_key(request: Request) -> None:
        expected = str(_manager_config().get("api_key") or "")
        if not expected:
            return
        auth = request.headers.get("authorization", "")
        if not hmac.compare_digest(auth, f"Bearer {expected}"):
            raise HTTPException(401, "invalid manager api key")

    @router.get("/display/status")
    async def display_status(request: Request) -> dict:
        _require_key(request)
        sock_path = _rfb_socket_path()
        running = os.path.exists(sock_path)
        lease = _lease_state()
        geometry = {"width": 0, "height": 0}
        if running:
            try:
                width, height = await asyncio.to_thread(rfb_geometry, sock_path)
                geometry = {"width": width, "height": height}
            except Exception as exc:
                LOG.debug("geometry probe failed: %r", exc)
        return {
            "running": running,
            "socket": sock_path if running else "",
            "holder": lease["holder"],
            "viewer_id": lease["viewer_id"],
            "geometry": geometry,
            "profiles": ["default"],
        }

    @router.post("/display/ticket")
    async def display_ticket(request: Request) -> dict:
        _require_key(request)
        if not os.path.exists(_rfb_socket_path()):
            raise HTTPException(409, "screen not running")
        ticket, viewer_id, ttl = mint_ticket()
        return {"ticket": ticket, "viewer_id": viewer_id, "expires_in": ttl}

    @router.post("/display/takeover")
    async def display_takeover(request: Request) -> dict:
        _require_key(request)
        try:
            payload = await request.json()
        except Exception:
            payload = {}
        viewer = str((payload or {}).get("viewer_id") or "hermeshub-app").strip() or "hermeshub-app"
        try:
            lease_mod = _lease_module()
            lease = lease_mod.acquire(viewer, reason="hermeshub takeover")
        except Exception as exc:
            raise HTTPException(500, f"lease acquire failed: {exc}")
        return {"holder": str(getattr(lease, "holder", "")), "viewer_id": str(getattr(lease, "viewer_id", ""))}

    @router.post("/display/release")
    async def display_release(request: Request) -> dict:
        _require_key(request)
        try:
            payload = await request.json()
        except Exception:
            payload = {}
        viewer = str((payload or {}).get("viewer_id") or "").strip() or None
        try:
            lease_mod = _lease_module()
            lease = lease_mod.release(viewer)
        except Exception as exc:
            raise HTTPException(500, f"lease release failed: {exc}")
        return {"holder": str(getattr(lease, "holder", "")), "viewer_id": str(getattr(lease, "viewer_id", ""))}

    @router.get("/display/frame.png")
    async def display_frame(request: Request, width: int = 960) -> Response:
        _require_key(request)
        if not _PIL:
            raise HTTPException(500, "Pillow unavailable")
        _touch_official_activity()
        try:
            png, _, _ = await asyncio.to_thread(
                rfb_snapshot_png, _rfb_socket_path(), max(0, min(int(width), 1920)))
        except Exception as exc:
            raise HTTPException(502, f"frame capture failed: {exc}")
        return Response(content=png, media_type="image/png")

    @router.websocket("/display/ws")
    async def display_ws(websocket: WebSocket) -> None:
        ticket = websocket.query_params.get("display_ticket", "")
        viewer_id = consume_ticket(ticket)
        if viewer_id is None:
            await websocket.close(code=4403, reason="bad display ticket")
            return
        await websocket.accept()
        sock_path = _rfb_socket_path()
        rfb = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        try:
            rfb.connect(sock_path)
            rfb.setblocking(False)
        except Exception as exc:
            LOG.warning("rfb connect failed: %r", exc)
            try:
                rfb.close()
            except Exception:
                pass
            await websocket.close(code=4411, reason="rfb unavailable")
            return
        try:
            await _bridge(websocket, rfb, viewer_id)
        finally:
            try:
                rfb.close()
            except Exception:
                pass


async def _bridge(websocket, rfb: socket.socket, viewer_id: str) -> None:
    """Splice RFB <-> WS. Input gated by the official lease file.

    Uses loop.sock_* primitives on a non-blocking socket (no StreamReader:
    framing is byte-oriented and partial by design). The bridge never
    handshakes itself: both handshakes belong to the viewer (noVNC), bytes
    are pure passthrough. With None-auth servers the client handshake prefix
    is exactly 14 bytes (version + auth select + ClientInit) and passes
    through uninspected; §6.4 framing starts after it.
    """
    pending = bytearray()
    handshake_remaining = 14
    last_lease_check = 0.0
    allowed_cache = False
    beat = 0
    loop = asyncio.get_running_loop()

    def lease_allows_input() -> bool:
        nonlocal last_lease_check, allowed_cache
        now = time.monotonic()
        if now - last_lease_check < 0.25 and last_lease_check > 0:
            return allowed_cache
        last_lease_check = now
        state = _lease_state()
        allowed_cache = state["holder"] == "human" and (state["viewer_id"] in ("", viewer_id))
        return allowed_cache

    rfb_queue: asyncio.Queue = asyncio.Queue(maxsize=256)

    def _on_rfb_readable() -> None:
        try:
            chunk = rfb.recv(65536)
        except (BlockingIOError, InterruptedError):
            return
        except Exception:
            loop.remove_reader(rfb.fileno())
            rfb_queue.put_nowait(None)
            return
        if not chunk:
            loop.remove_reader(rfb.fileno())
            rfb_queue.put_nowait(None)
            return
        try:
            rfb_queue.put_nowait(chunk)
        except asyncio.QueueFull:
            pass

    async def ws_to_rfb() -> None:
        nonlocal pending, handshake_remaining
        try:
            while True:
                message = await websocket.receive()
                if message.get("type") == "websocket.disconnect":
                    return
                data = message.get("bytes")
                if data is None:
                    text = message.get("text")
                    if text is None:
                        continue
                    data = text.encode("utf-8")
                pending += data
                if len(pending) > _MAX_PENDING:
                    LOG.warning("client flood; closing viewer %s", viewer_id)
                    return
                if handshake_remaining > 0:
                    take = min(handshake_remaining, len(pending))
                    chunk = bytes(pending[:take])
                    del pending[:take]
                    handshake_remaining -= take
                    try:
                        await loop.sock_sendall(rfb, chunk)
                    except Exception:
                        return
                    continue
                while True:
                    consumed, is_input, complete, fatal = parse_client_message(bytes(pending))
                    if fatal:
                        LOG.warning("rbf protocol violation; closing viewer %s", viewer_id)
                        return
                    if not complete:
                        break
                    chunk = bytes(pending[:consumed])
                    del pending[:consumed]
                    if is_input and not lease_allows_input():
                        continue  # dropped at the gate; view stays live
                    try:
                        await loop.sock_sendall(rfb, chunk)
                    except Exception:
                        return
        except Exception:
            return

    async def rfb_to_ws() -> None:
        try:
            loop.add_reader(rfb.fileno(), _on_rfb_readable)
            while True:
                chunk = await rfb_queue.get()
                if chunk is None:
                    return
                try:
                    await websocket.send_bytes(chunk)
                except Exception:
                    return
        except Exception:
            return
        finally:
            try:
                loop.remove_reader(rfb.fileno())
            except Exception:
                pass

    _touch_official_activity()

    async def watch_eviction() -> None:
        nonlocal beat
        try:
            while True:
                await asyncio.sleep(1.0)
                beat += 1
                if beat % 60 == 0:
                    _touch_official_activity()
                state = await loop.run_in_executor(None, _lease_state)
                if state["holder"] == "human" and state["viewer_id"] not in ("", viewer_id):
                    try:
                        await websocket.close(code=4000, reason="control-taken")
                    except Exception:
                        pass
                    return
        except Exception:
            return

    tasks = [asyncio.create_task(ws_to_rfb()), asyncio.create_task(rfb_to_ws()),
             asyncio.create_task(watch_eviction())]
    try:
        await asyncio.wait(tasks, return_when=asyncio.FIRST_COMPLETED)
    finally:
        for task in tasks:
            if not task.done():
                task.cancel()
