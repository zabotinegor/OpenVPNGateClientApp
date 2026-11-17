#!/usr/bin/env python3
"""Verifies the compiled core BuildConfig.PRIMARY_SERVERS_URL equals the expected origin.

Usage: inspect-buildconfig-origin.py <expected-origin> [<buildconfig-root>]

<buildconfig-root> defaults to src/core/build/generated/source/buildConfig/release. Exactly one
core/BuildConfig.java must exist beneath it and it must declare PRIMARY_SERVERS_URL exactly once.
Fails closed (non-zero) when the file is missing or ambiguous, or the value does not match.
Exit codes: 0 match, 1 verification failure, 2 usage error.
"""
import re
import sys
from pathlib import Path

DEFAULT_ROOT = "src/core/build/generated/source/buildConfig/release"
ORIGIN_RE = re.compile(r"^https://[A-Za-z0-9.-]+/?$")
FIELD_RE = re.compile(r'\bPRIMARY_SERVERS_URL\s*=\s*"([^"]*)"\s*;')


def main(argv):
    if len(argv) not in (2, 3):
        print("::error::usage: inspect-buildconfig-origin.py <expected-origin> [<buildconfig-root>]")
        return 2
    if not ORIGIN_RE.match(argv[1]):
        print("::error::expected origin must be a bare https origin (https://host), got: %r" % argv[1])
        return 2
    expected = argv[1].rstrip("/")
    root = Path(argv[2] if len(argv) == 3 else DEFAULT_ROOT)

    files = sorted(p for p in root.glob("**/core/BuildConfig.java") if p.is_file()) if root.is_dir() else []
    if not files:
        print("::error::No core/BuildConfig.java found under %s." % root)
        return 1
    if len(files) > 1:
        print("::error::Ambiguous: %d core/BuildConfig.java files under %s." % (len(files), root))
        return 1

    values = FIELD_RE.findall(files[0].read_text(encoding="utf-8", errors="replace"))
    if not values:
        print("::error::PRIMARY_SERVERS_URL not found in %s." % files[0])
        return 1
    if len(values) > 1:
        print("::error::Ambiguous: PRIMARY_SERVERS_URL declared %d times in %s." % (len(values), files[0]))
        return 1

    found = values[0]
    print("Compiled BuildConfig.PRIMARY_SERVERS_URL = %s" % found)
    if found != expected:
        print("::error::Compiled PRIMARY_SERVERS_URL '%s' does not equal the expected origin '%s'." % (found, expected))
        return 1
    print("Compiled BuildConfig.PRIMARY_SERVERS_URL matches the expected origin.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
