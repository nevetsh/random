"""Checks that every setting description in the config screen stays short.

The config screen draws a description under the setting it belongs to, one line, so a
description longer than 41 characters is cut off on the common GUI scales. Run it from
the project root:

    python tools/check_descriptions.py

It exits non-zero when any description is too long.
"""

import pathlib
import re
import sys

LIMIT = 41
ROOT = pathlib.Path(__file__).resolve().parent.parent / "src" / "main" / "kotlin"

# Where the description sits among the string arguments of each call: every call starts
# with the config name, and the display name follows the description.
DESCRIPTION_AT = {
    "Feature": 1,
    "addSwitch": 1,
    "addSlider": 1,
    "addText": 2,  # addText takes its initial value as a string too
    "addButton": 1,
}

STRING = re.compile(r'"((?:[^"\\]|\\.)*)"')


def strings_of(source: str, open_paren: int):
    """The string literals of one call, found by walking its balanced parentheses."""
    depth = 0
    for pos in range(open_paren, len(source)):
        char = source[pos]
        if char == '"':
            match = STRING.match(source, pos)
            if match:
                pos = match.end() - 1
                continue
        if char == "(":
            depth += 1
        elif char == ")":
            depth -= 1
            if depth == 0:
                return [m.group(1) for m in STRING.finditer(source, open_paren, pos)]
    return []


def main() -> int:
    too_long = []
    checked = 0
    longest = 0
    for file in sorted(ROOT.rglob("*.kt")):
        source = file.read_text(encoding="utf-8")
        for name, index in DESCRIPTION_AT.items():
            for match in re.finditer(r"\b" + name + r"\s*\(", source):
                strings = strings_of(source, match.end() - 1)
                if len(strings) <= index:
                    continue
                description = strings[index]
                checked += 1
                longest = max(longest, len(description))
                if len(description) > LIMIT:
                    too_long.append((file.name, name, description, len(description)))
    for file, name, description, length in too_long:
        print(f"{file}: {name} description is {length} chars: {description}")
    print(f"checked {checked} descriptions, longest is {longest} chars")
    return 1 if too_long else 0


if __name__ == "__main__":
    sys.exit(main())
