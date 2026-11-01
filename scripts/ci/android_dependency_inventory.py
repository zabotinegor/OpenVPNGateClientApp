#!/usr/bin/env python3
"""Produce a deterministic read-only Android dependency declaration inventory.

Runs without Gradle, network access, signing keys or local Android SDK.
It inventories *declarations*, not resolved transitive dependencies or vulnerabilities.
"""
from __future__ import annotations

import argparse
import json
import re
import tomllib
from pathlib import Path

SKIP_DIRS = {".git", ".gradle", "build", ".idea", "node_modules", "external"}
ACCESSOR = re.compile(r"\blibs\.(plugins\.)?([a-zA-Z][a-zA-Z0-9_.]*)")
GRADLE_COORDINATE = re.compile(
    r"""\b(?:implementation|api|compileOnly|runtimeOnly|testImplementation|androidTestImplementation|debugImplementation|kapt|ksp)\(\s*["']([^"'$]+:[^"'$]+:[^"'$]+)["']"""
)
CONFIG_VERSION = re.compile(
    r"\b(compileSdk|targetSdk|minSdk|ndkVersion|jvmTarget|sourceCompatibility|targetCompatibility)\s*=\s*(?:\"([^\"]+)\"|(\d+)|JavaVersion\.(VERSION_[\d_]+))"
)
GHA_ACTION = re.compile(r"^\s*-?\s*uses:\s*([^\s#]+)", re.MULTILINE)
GRADLE_WRAPPER = re.compile(r"^distributionUrl=(.+)$", re.MULTILINE)
GRADLE_DIST = re.compile(r"gradle-([0-9][^-]*)-(?:bin|all)\.zip")
TOOLS = re.compile(r"""\b(?:java-version|ndk|cmake|gradle-version|build-tools|distribution):\s*['"]?([^\s'"]+)""")


def _item(kind: str, path: str, name: str, version: str, detail: str = "") -> dict[str, str]:
    return {"kind": kind, "path": path, "name": name, "version": str(version), "detail": detail}


def _files(root: Path, pattern: str) -> list[Path]:
    return sorted(p for p in root.rglob(pattern) if p.is_file()
                  and not SKIP_DIRS.intersection(p.relative_to(root).parts))


def collect(root: Path) -> tuple[list[dict[str, str]], list[str]]:
    root = root.resolve()
    catalog_path = root / "src/gradle/libs.versions.toml"
    catalog = tomllib.loads(catalog_path.read_text(encoding="utf-8"))
    versions = catalog.get("versions", {})
    libraries = catalog.get("libraries", {})
    plugins = catalog.get("plugins", {})
    entries: list[dict[str, str]] = []
    errors: list[str] = []
    cp = catalog_path.relative_to(root).as_posix()
    for name, value in sorted(versions.items()):
        entries.append(_item("catalog-version", cp, name, str(value)))
    for kind, collection in (("library", libraries), ("plugin", plugins)):
        for name, value in sorted(collection.items()):
            if not isinstance(value, dict):
                errors.append(f"{kind} {name}: expected TOML table")
                continue
            version = value.get("version", {})
            ref = version.get("ref") if isinstance(version, dict) else None
            resolved = versions.get(ref) if ref else version
            if ref and ref not in versions:
                errors.append(f"{kind} {name}: undefined version.ref {ref}")
            coordinate = (value.get("group", "") + ":" + value.get("name", name)) if kind == "library" else value.get("id", name)
            entries.append(_item("catalog-" + kind, cp, coordinate, str(resolved or "(missing)"), f"alias={name}; ref={ref or ''}"))
    for path in _files(root / "src", "*.gradle.kts") if (root / "src").is_dir() else []:
        rel = path.relative_to(root).as_posix()
        contents = path.read_text(encoding="utf-8")
        for match in ACCESSOR.finditer(contents):
            is_plugin = bool(match.group(1))
            accessor = match.group(2).rstrip(".")
            alias = accessor.replace(".", "-")
            collection = plugins if is_plugin else libraries
            if alias not in collection:
                errors.append(f"{rel}: unresolved catalog alias libs.{ 'plugins.' if is_plugin else ''}{accessor}")
        for match in GRADLE_COORDINATE.finditer(contents):
            entries.append(_item("direct-coordinate", rel, match.group(1), match.group(1).split(":")[-1]))
        for match in CONFIG_VERSION.finditer(contents):
            entries.append(_item("android-build-setting", rel, match.group(1), next((g for g in match.groups()[1:] if g), "")))
    wrapper = root / "src/gradle/wrapper/gradle-wrapper.properties"
    if wrapper.is_file():
        text = wrapper.read_text(encoding="utf-8")
        m = GRADLE_WRAPPER.search(text)
        if m:
            url = m.group(1).replace("\\:", ":")
            dist = GRADLE_DIST.search(url)
            entries.append(_item("gradle-wrapper", wrapper.relative_to(root).as_posix(),
                                 "Gradle", dist.group(1) if dist else "(unrecognized)", url))
        else:
            errors.append("Gradle wrapper distributionUrl missing")
    for ext in ("*.yml", "*.yaml"):
        for path in _files(root / ".github/workflows", ext) if (root / ".github/workflows").is_dir() else []:
            rel = path.relative_to(root).as_posix()
            contents = path.read_text(encoding="utf-8")
            for match in GHA_ACTION.finditer(contents):
                action = match.group(1)
                entries.append(_item("github-action", rel, action.split("@")[0], action.split("@")[-1]))
            for match in TOOLS.finditer(contents):
                entries.append(_item("ci-toolchain", rel, match.group(0).split(":")[0].strip(), match.group(1)))
    for filename in (".gitmodules",):
        path = root / filename
        if path.is_file():
            for line in path.read_text(encoding="utf-8").splitlines():
                if line.strip().startswith(("path =", "branch =", "url =")):
                    key, val = [v.strip() for v in line.split("=", 1)]
                    entries.append(_item("submodule-config", filename, key, val))
    entries.sort(key=lambda e: (e["kind"], e["path"], e["name"], e["version"], e["detail"]))
    return entries, sorted(set(errors))


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument("--format", choices=("json", "markdown"), default="json")
    parser.add_argument("--check", action="store_true", help="exit nonzero for unresolved aliases or missing refs")
    args = parser.parse_args()
    entries, errors = collect(args.root)
    if args.format == "json":
        print(json.dumps({"items": entries, "errors": errors}, ensure_ascii=False, indent=2))
    else:
        print("| Kind | File | Dependency | Version | Detail |")
        print("| --- | --- | --- | --- | --- |")
        for entry in entries:
            print("| " + " | ".join(str(entry[field]).replace("|", "\\|") for field in ("kind", "path", "name", "version", "detail")) + " |")
        for error in errors:
            print("ERROR: " + error)
    if args.check and errors:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
