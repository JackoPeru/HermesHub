#!/usr/bin/env python3
"""Validate Hermes Hub Protocol v1 schema and golden fixture."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any

from jsonschema import Draft202012Validator


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--schema", type=Path, default=Path("config/hermes-hub-protocol.schema.json"))
    parser.add_argument(
        "--fixture",
        type=Path,
        default=Path("tests/contracts/hermes-hub-protocol-fixture.json"),
    )
    return parser.parse_args()


def load_json(path: Path) -> Any:
    with path.open("r", encoding="utf-8") as stream:
        return json.load(stream)


def validate_protocol(schema_path: Path, fixture_path: Path) -> None:
    schema = load_json(schema_path)
    fixture = load_json(fixture_path)
    Draft202012Validator.check_schema(schema)
    validator = Draft202012Validator(schema)
    errors = sorted(validator.iter_errors(fixture), key=lambda error: list(error.absolute_path))
    if errors:
        messages = []
        for error in errors:
            location = ".".join(str(part) for part in error.absolute_path) or "<root>"
            messages.append(f"{location}: {error.message}")
        raise ValueError("Hermes Hub Protocol fixture is invalid:\n" + "\n".join(messages))

    envelope = fixture["event_envelope"]
    unknown = fixture["unknown_event_envelope"]
    if envelope["request_id"] != unknown["request_id"] or envelope["correlation_id"] != unknown["correlation_id"]:
        raise ValueError("Golden fixture must keep request_id/correlation_id stable across events.")
    if unknown["type"] == envelope["type"]:
        raise ValueError("Golden fixture must include an unknown event type.")
    headers = fixture["correlation_headers"]
    if headers != {
        "request_id": "X-Hermes-Request-Id",
        "correlation_id": "X-Hermes-Correlation-Id",
        "compatibility_request_id": "X-Request-Id",
    }:
        raise ValueError("Golden fixture must expose the canonical correlation headers.")
    if fixture["visual_blocks"]["fallback_field"] != "output_text":
        raise ValueError("Visual Blocks fallback must remain output_text.")


def main() -> int:
    args = parse_args()
    validate_protocol(args.schema, args.fixture)
    print("Hermes Hub Protocol v1 schema and golden fixture OK.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
