#!/usr/bin/env python3
"""Reproducible synthetic benchmark for the Hermes Jarvis fast observer."""

from __future__ import annotations

import argparse
import base64
import json
import math
import os
import statistics
import struct
import tempfile
import time
import urllib.error
import urllib.request
import zlib
from collections import Counter
from pathlib import Path
from typing import Any

from jsonschema import Draft202012Validator


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_MANIFEST = ROOT / "tests" / "fixtures" / "jarvis-benchmark" / "manifest-v1.json"
MANIFEST_SCHEMA = ROOT / "config" / "jarvis-benchmark-manifest.schema.json"
OUTPUT_SCHEMA = ROOT / "config" / "jarvis-observer-output.schema.json"

GLYPHS = {
    "A": ("01110", "10001", "10001", "11111", "10001", "10001", "10001"),
    "B": ("11110", "10001", "10001", "11110", "10001", "10001", "11110"),
    "C": ("01111", "10000", "10000", "10000", "10000", "10000", "01111"),
    "D": ("11110", "10001", "10001", "10001", "10001", "10001", "11110"),
    "E": ("11111", "10000", "10000", "11110", "10000", "10000", "11111"),
    "F": ("11111", "10000", "10000", "11110", "10000", "10000", "10000"),
    "G": ("01111", "10000", "10000", "10111", "10001", "10001", "01111"),
    "H": ("10001", "10001", "10001", "11111", "10001", "10001", "10001"),
    "I": ("11111", "00100", "00100", "00100", "00100", "00100", "11111"),
    "J": ("00111", "00010", "00010", "00010", "10010", "10010", "01100"),
    "K": ("10001", "10010", "10100", "11000", "10100", "10010", "10001"),
    "L": ("10000", "10000", "10000", "10000", "10000", "10000", "11111"),
    "M": ("10001", "11011", "10101", "10101", "10001", "10001", "10001"),
    "N": ("10001", "11001", "10101", "10011", "10001", "10001", "10001"),
    "O": ("01110", "10001", "10001", "10001", "10001", "10001", "01110"),
    "P": ("11110", "10001", "10001", "11110", "10000", "10000", "10000"),
    "Q": ("01110", "10001", "10001", "10001", "10101", "10010", "01101"),
    "R": ("11110", "10001", "10001", "11110", "10100", "10010", "10001"),
    "S": ("01111", "10000", "10000", "01110", "00001", "00001", "11110"),
    "T": ("11111", "00100", "00100", "00100", "00100", "00100", "00100"),
    "U": ("10001", "10001", "10001", "10001", "10001", "10001", "01110"),
    "V": ("10001", "10001", "10001", "10001", "10001", "01010", "00100"),
    "W": ("10001", "10001", "10001", "10101", "10101", "10101", "01010"),
    "X": ("10001", "10001", "01010", "00100", "01010", "10001", "10001"),
    "Y": ("10001", "10001", "01010", "00100", "00100", "00100", "00100"),
    "Z": ("11111", "00001", "00010", "00100", "01000", "10000", "11111"),
    "0": ("01110", "10001", "10011", "10101", "11001", "10001", "01110"),
    "1": ("00100", "01100", "00100", "00100", "00100", "00100", "01110"),
    "2": ("01110", "10001", "00001", "00010", "00100", "01000", "11111"),
    "3": ("11110", "00001", "00001", "01110", "00001", "00001", "11110"),
    "4": ("00010", "00110", "01010", "10010", "11111", "00010", "00010"),
    "5": ("11111", "10000", "10000", "11110", "00001", "00001", "11110"),
    "6": ("01110", "10000", "10000", "11110", "10001", "10001", "01110"),
    "7": ("11111", "00001", "00010", "00100", "01000", "01000", "01000"),
    "8": ("01110", "10001", "10001", "01110", "10001", "10001", "01110"),
    "9": ("01110", "10001", "10001", "01111", "00001", "00001", "01110"),
    "+": ("00000", "00100", "00100", "11111", "00100", "00100", "00000"),
    "-": ("00000", "00000", "00000", "11111", "00000", "00000", "00000"),
    ".": ("00000", "00000", "00000", "00000", "00000", "00110", "00110"),
    ":": ("00000", "00110", "00110", "00000", "00110", "00110", "00000"),
    " ": ("00000",) * 7,
}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", type=Path, default=DEFAULT_MANIFEST)
    parser.add_argument("--validate-only", action="store_true")
    parser.add_argument("--base-url")
    parser.add_argument("--model")
    parser.add_argument("--api-key-env", default="HERMES_JARVIS_REASONING_API_KEY")
    parser.add_argument("--timeout", type=float, default=30.0)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--keep-rendered", type=Path)
    return parser.parse_args()


def load_and_validate(path: Path) -> tuple[dict[str, Any], dict[str, Any]]:
    manifest = json.loads(path.read_text(encoding="utf-8"))
    manifest_schema = json.loads(MANIFEST_SCHEMA.read_text(encoding="utf-8"))
    output_schema = json.loads(OUTPUT_SCHEMA.read_text(encoding="utf-8"))
    Draft202012Validator(manifest_schema).validate(manifest)
    return manifest, output_schema


def color(value: str) -> tuple[int, int, int]:
    return tuple(int(value[index : index + 2], 16) for index in (1, 3, 5))  # type: ignore[return-value]


def rectangle(pixels: bytearray, width: int, height: int, box: tuple[int, int, int, int], rgb: tuple[int, int, int]) -> None:
    left, top, right, bottom = box
    for y in range(max(0, top), min(height, bottom)):
        for x in range(max(0, left), min(width, right)):
            offset = (y * width + x) * 3
            pixels[offset : offset + 3] = bytes(rgb)


def draw_text(pixels: bytearray, width: int, height: int, text: str, x: int, y: int, scale: int = 5) -> None:
    for char in text.upper()[:24]:
        glyph = GLYPHS.get(char, GLYPHS[" "])
        for row, pattern in enumerate(glyph):
            for column, enabled in enumerate(pattern):
                if enabled == "1":
                    rectangle(
                        pixels,
                        width,
                        height,
                        (x + column * scale, y + row * scale, x + (column + 1) * scale, y + (row + 1) * scale),
                        (245, 248, 252),
                    )
        x += 6 * scale


def png_chunk(kind: bytes, data: bytes) -> bytes:
    return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)


def render_frame(frame: dict[str, Any]) -> bytes:
    width, height = 512, 320
    pixels = bytearray(bytes((18, 22, 29)) * width * height)
    accent = color(frame["accent"])
    variant = int(frame["variant"])
    rectangle(pixels, width, height, (28, 28, 484, 292), (35, 42, 52))
    rectangle(pixels, width, height, (58 + variant % 25, 72, 224, 230), accent)
    if frame["scene"] in {"connector", "assembly", "workbench"}:
        rectangle(pixels, width, height, (265, 86 + variant % 19, 443, 124 + variant % 19), (105, 116, 132))
        rectangle(pixels, width, height, (286, 157, 423, 218), (64, 73, 86))
    if frame["state"] == "error":
        rectangle(pixels, width, height, (22, 22, 490, 34), (202, 52, 52))
        rectangle(pixels, width, height, (22, 286, 490, 298), (202, 52, 52))
    draw_text(pixels, width, height, frame["caption"], 48, 246, 5)
    scanlines = bytearray()
    for row in range(height):
        scanlines.append(0)
        start = row * width * 3
        scanlines.extend(pixels[start : start + width * 3])
    signature = b"\x89PNG\r\n\x1a\n"
    header = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)
    return signature + png_chunk(b"IHDR", header) + png_chunk(b"IDAT", zlib.compress(bytes(scanlines), 9)) + png_chunk(b"IEND", b"")


def endpoint(base_url: str) -> str:
    clean = base_url.rstrip("/")
    if clean.endswith("/chat/completions"):
        return clean
    return clean + ("/chat/completions" if clean.endswith("/v1") else "/v1/chat/completions")


def invoke(case: dict[str, Any], rendered: list[tuple[dict[str, Any], bytes]], args: argparse.Namespace) -> tuple[Any, float, dict[str, Any]]:
    content: list[dict[str, Any]] = [{
        "type": "text",
        "text": (
            "You are Hermes Jarvis visual observer and router. Return one JSON object only; no markdown. "
            "Required keys: action, observation, reason, confidence, importance, urgency, utility, reply, "
            "needs_agent, event_key, memory_update, recommended_frame_ids. Allowed actions: ignore, respond_simple, ask_user, escalate, "
            "urgent_candidate. Scores are 0..1. If Question is empty: respond_simple is forbidden; questions_only "
            "always means ignore; stable or merely descriptive scenes must be ignore. Speak only for a new goal-relevant "
            "change, ambiguity, or credible error. If Question is present: respond_simple only for obvious color, count, "
            "position, or clearly readable text. Use escalate for ambiguity, safety, money, devices, memory, tools, "
            "comparison, diagnosis, or multi-step work. Use urgent_candidate only for a credible time-sensitive visible "
            "risk. recommended_frame_ids may contain only "
            "exact Frame id values listed below, otherwise use [].\n"
            f"Mode: {case['mode']}\nGoal: {case['goal']}\nQuestion: {case['question'] or ''}\n"
            f"Recent context: {json.dumps(case['recent_context'], ensure_ascii=False)}"
        ),
    }]
    for frame, payload in rendered:
        encoded = base64.b64encode(payload).decode("ascii")
        content.append({"type": "text", "text": f"Frame id: {frame['id']}"})
        content.append({"type": "image_url", "image_url": {"url": f"data:image/png;base64,{encoded}"}})
    request_body = json.dumps({
        "model": args.model,
        "messages": [{"role": "user", "content": content}],
        "temperature": 0,
        "max_tokens": 256,
        "response_format": {"type": "json_object"},
        "chat_template_kwargs": {"enable_thinking": False},
        "stream": False,
    }).encode("utf-8")
    headers = {"Content-Type": "application/json", "User-Agent": "HermesHub-Jarvis-Benchmark"}
    token = os.environ.get(args.api_key_env, "").strip()
    if token:
        headers["Authorization"] = f"Bearer {token}"
    started = time.perf_counter()
    try:
        with urllib.request.urlopen(urllib.request.Request(endpoint(args.base_url), request_body, headers), timeout=args.timeout) as response:
            raw = json.load(response)
    except urllib.error.HTTPError as error:
        detail = error.read(4096).decode("utf-8", "replace")
        raise RuntimeError(f"HTTP {error.code}: {detail}") from error
    latency_ms = (time.perf_counter() - started) * 1000
    content_value = raw["choices"][0]["message"]["content"]
    if isinstance(content_value, list):
        content_value = "".join(item.get("text", "") for item in content_value if isinstance(item, dict))
    text_value = content_value.strip() if isinstance(content_value, str) else content_value
    if isinstance(text_value, str) and text_value.startswith("```"):
        first_newline = text_value.find("\n")
        if first_newline < 0 or not text_value.endswith("```"):
            raise ValueError(f"invalid model JSON fence: {repr(text_value[:240])}")
        text_value = text_value[first_newline + 1 : -3].strip()
    try:
        parsed = json.loads(text_value)
    except (TypeError, json.JSONDecodeError) as error:
        preview = repr(text_value[:240] if isinstance(text_value, str) else text_value)
        raise ValueError(f"invalid model JSON: {preview}") from error
    return parsed, latency_ms, raw.get("timings") or {}


def percentile(values: list[float], fraction: float) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    index = max(0, min(len(ordered) - 1, math.ceil(fraction * len(ordered)) - 1))
    return round(ordered[index], 2)


def score(results: list[dict[str, Any]]) -> dict[str, Any]:
    valid = [item for item in results if item["valid_json"]]
    total = len(results)
    action_correct = sum(item["action_correct"] for item in valid)
    expected_silent = [item for item in valid if not item["expected_speak"]]
    expected_speak = [item for item in valid if item["expected_speak"]]
    simple = [item for item in valid if item["category"] in {"simple_question", "text_reading"}]
    latencies = [item["latency_ms"] for item in results if item.get("latency_ms") is not None]
    return {
        "cases": total,
        "valid_json_rate": round(len(valid) / total, 4) if total else 0,
        "action_accuracy": round(action_correct / len(valid), 4) if valid else 0,
        "false_positive_rate": round(sum(item["predicted_speak"] for item in expected_silent) / len(expected_silent), 4) if expected_silent else 0,
        "false_negative_rate": round(sum(not item["predicted_speak"] for item in expected_speak) / len(expected_speak), 4) if expected_speak else 0,
        "silence_rate": round(sum(not item["predicted_speak"] for item in expected_silent) / len(expected_silent), 4) if expected_silent else 0,
        "simple_reply_precision": round(sum(item["reply_correct"] for item in simple) / len(simple), 4) if simple else 0,
        "escalation_rate": round(sum(item.get("action") in {"escalate", "urgent_candidate"} for item in valid) / len(valid), 4) if valid else 0,
        "latency_ms": {"p50": percentile(latencies, 0.50), "p95": percentile(latencies, 0.95), "mean": round(statistics.mean(latencies), 2) if latencies else None},
    }


def recommend_thresholds(results: list[dict[str, Any]], cases: list[dict[str, Any]]) -> dict[str, Any]:
    by_id = {item["id"]: item for item in results if item["valid_json"] and isinstance(item.get("output"), dict)}
    calibration = [case for case in cases if case["split"] == "calibration" and case["question"] is None and case["id"] in by_id]
    recommendations: dict[str, Any] = {}
    for mode, disturbance in (("assistive", 0.35), ("proactive", 0.20)):
        best = (float("-inf"), 0.5)
        for step in range(101):
            cutoff = step / 100
            correct = 0
            for case in calibration:
                output = by_id[case["id"]]["output"]
                raw = float(output["importance"]) * float(output["urgency"]) * float(output["confidence"])
                predicted = output["action"] != "ignore" and raw >= cutoff
                expected = bool(case["expected"]["should_speak_by_mode"][mode])
                correct += predicted == expected
            accuracy = correct / len(calibration) if calibration else 0
            if accuracy > best[0] or (accuracy == best[0] and cutoff > best[1]):
                best = (accuracy, cutoff)
        recommendations[f"{mode}_threshold"] = round(best[1] - disturbance, 2)
        recommendations[f"{mode}_calibration_accuracy"] = round(best[0], 4)
    simple_confidences = [
        float(item["output"]["confidence"])
        for item in results
        if item["valid_json"]
        and item["split"] == "calibration"
        and item["category"] in {"simple_question", "text_reading"}
        and item["action_correct"]
        and item["reply_correct"]
    ]
    recommendations["simple_min_confidence"] = round(min(simple_confidences), 2) if simple_confidences else None
    recommendations["provisional"] = not bool(calibration and simple_confidences)
    return recommendations


def evaluate(case: dict[str, Any], output: Any, latency_ms: float | None, validator: Draft202012Validator) -> dict[str, Any]:
    errors = sorted(validator.iter_errors(output), key=lambda item: list(item.path)) if isinstance(output, dict) else ["not_object"]
    valid = not errors
    action = output.get("action") if valid else None
    reply = str(output.get("reply") or "").lower() if valid else ""
    expected = case["expected"]
    terms_any = [item.lower() for item in expected["reply_terms_any"]]
    terms_all = [item.lower() for item in expected["reply_terms_all"]]
    terms_none = [item.lower() for item in expected["reply_terms_none"]]
    reply_correct = (
        (not terms_any or any(item in reply for item in terms_any))
        and all(item in reply for item in terms_all)
        and not any(item in reply for item in terms_none)
    )
    predicted_speak = action in {"respond_simple", "ask_user", "escalate", "urgent_candidate"}
    return {
        "id": case["id"],
        "split": case["split"],
        "category": case["category"],
        "valid_json": valid,
        "validation_errors": [str(item)[:300] for item in errors],
        "action": action,
        "action_correct": action in expected["actions"],
        "expected_speak": expected["should_speak_by_mode"][case["mode"]],
        "predicted_speak": predicted_speak,
        "reply_correct": reply_correct,
        "latency_ms": round(latency_ms, 2) if latency_ms is not None else None,
        "output": output,
    }


def main() -> int:
    args = parse_args()
    manifest, output_schema = load_and_validate(args.manifest)
    output_validator = Draft202012Validator(output_schema)
    owned_temp = None
    render_root = args.keep_rendered
    if render_root is None:
        owned_temp = tempfile.TemporaryDirectory(prefix="hermes-jarvis-benchmark-")
        render_root = Path(owned_temp.name)
    render_root.mkdir(parents=True, exist_ok=True)
    rendered_cases: dict[str, list[tuple[dict[str, Any], bytes]]] = {}
    for case in manifest["cases"]:
        entries = []
        for frame in case["frames"]:
            payload = render_frame(frame)
            if not payload.startswith(b"\x89PNG\r\n\x1a\n"):
                raise RuntimeError("Renderer PNG non valido")
            target = render_root / f"{case['id']}-{frame['id']}.png"
            target.write_bytes(payload)
            entries.append((frame, payload))
        rendered_cases[case["id"]] = entries
    if args.validate_only:
        print(json.dumps({"valid": True, "cases": len(manifest["cases"]), "rendered_frames": sum(map(len, rendered_cases.values()))}, indent=2))
        if owned_temp:
            owned_temp.cleanup()
        return 0
    if not args.base_url or not args.model:
        raise SystemExit("--base-url e --model sono obbligatori senza --validate-only")
    results = []
    for index, case in enumerate(manifest["cases"], 1):
        try:
            output, latency, timings = invoke(case, rendered_cases[case["id"]], args)
            item = evaluate(case, output, latency, output_validator)
            item["server_timings"] = timings
        except Exception as error:
            item = evaluate(case, None, None, output_validator)
            item["request_error"] = str(error)[:500]
        results.append(item)
        print(f"[{index:02d}/50] {case['id']}: {item.get('action') or 'ERROR'}")
    report = {
        "schema_version": 1,
        "model": args.model,
        "endpoint": "explicit",
        "dataset": {"cases": len(manifest["cases"]), "categories": dict(Counter(item["category"] for item in manifest["cases"]))},
        "metrics": score(results),
        "splits": {
            split: score([item for item in results if item["split"] == split])
            for split in ("calibration", "holdout")
        },
        "recommended_gateway_thresholds": recommend_thresholds(results, manifest["cases"]),
        "results": results,
    }
    encoded = json.dumps(report, ensure_ascii=False, indent=2)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(encoded + "\n", encoding="utf-8")
    print(encoded)
    if owned_temp:
        owned_temp.cleanup()
    return 0 if report["metrics"]["valid_json_rate"] == 1.0 else 2


if __name__ == "__main__":
    raise SystemExit(main())
