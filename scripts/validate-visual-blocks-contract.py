#!/usr/bin/env python3
"""Validate the Visual Blocks fixture and schema with Draft 2020-12."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any

from jsonschema import Draft202012Validator


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--schema", type=Path, default=Path("config/visual-blocks.schema.json"))
    parser.add_argument("--fixture", type=Path, default=Path("tests/contracts/visual-blocks-fixture.json"))
    return parser.parse_args()


def load_json(path: Path) -> Any:
    with path.open("r", encoding="utf-8") as stream:
        return json.load(stream)


def schema_block_types(schema: dict[str, Any]) -> set[str]:
    definitions = schema.get("$defs", {})
    references = definitions.get("block", {}).get("oneOf", [])
    result: set[str] = set()
    for reference in references:
        ref = reference.get("$ref", "")
        name = ref.removeprefix("#/$defs/")
        block = definitions.get(name, {})
        for part in block.get("allOf", []):
            value = part.get("properties", {}).get("type", {}).get("const")
            if isinstance(value, str):
                result.add(value)
    return result


def validate_contract(schema_path: Path, fixture_path: Path) -> None:
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
        raise ValueError("Visual Blocks fixture is invalid:\n" + "\n".join(messages))

    expected_types = schema_block_types(schema)
    fixture_types = {
        block.get("type")
        for block in fixture.get("visual_blocks", [])
        if isinstance(block, dict) and isinstance(block.get("type"), str)
    }
    missing = expected_types - fixture_types
    if missing:
        raise ValueError(f"Visual Blocks fixture is missing schema types: {sorted(missing)}")


def main() -> int:
    args = parse_args()
    validate_contract(args.schema, args.fixture)
    print("Visual Blocks JSON Schema validation OK.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
