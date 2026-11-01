from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from android_dependency_inventory import collect  # noqa: E402


class AndroidDependencyInventoryTests(unittest.TestCase):
    @staticmethod
    def _write(root: Path, name: str, content: str) -> None:
        path = root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")

    def test_catalog_wrapper_modules_and_actions(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self._write(root, "src/gradle/libs.versions.toml", """
[versions]
agp = "8.13.2"
constraintlayout = "2.2.2"
[libraries]
androidx-constraintlayout = { group = "androidx.constraintlayout", name = "constraintlayout", version.ref = "constraintlayout" }
[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
""")
            self._write(root, "src/mobile/build.gradle.kts", """
plugins { alias(libs.plugins.android.application) }
android { compileSdk = 36; defaultConfig { minSdk = 24; targetSdk = 36 } }
dependencies { implementation(libs.androidx.constraintlayout) }
""")
            self._write(root, "src/gradle/wrapper/gradle-wrapper.properties",
                        "distributionUrl=https\\://services.gradle.org/distributions/gradle-8.13-bin.zip\n")
            self._write(root, ".github/workflows/pr.yml", """
steps:
  - uses: actions/checkout@v4
  - name: Set up JDK
    uses: actions/setup-java@v4
    with:
      java-version: '17'
""")
            self._write(root, ".gitmodules", "[submodule \"engine\"]\n path = src/external/OpenVPNEngine\n branch = OpenVPNClientApp-integration\n")
            items, errors = collect(root)
            self.assertEqual(errors, [])
            self.assertTrue(any(e["kind"] == "gradle-wrapper" and e["version"] == "8.13" for e in items))
            self.assertTrue(any(e["kind"] == "catalog-library" and
                                e["version"] == "2.2.2" for e in items))
            self.assertTrue(any(e["kind"] == "android-build-setting" and
                                e["name"] == "compileSdk" and e["version"] == "36" for e in items))
            self.assertTrue(any(e["kind"] == "github-action" and
                                e["name"] == "actions/checkout" and e["version"] == "v4" for e in items))
            self.assertEqual((items, errors), collect(root))

    def test_unknown_version_and_alias_fail_closed(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self._write(root, "src/gradle/libs.versions.toml", """
[versions]
kotlin = "2.0.21"
[libraries]
missing-ref = { group = "com.example", name = "lib", version.ref = "undefined" }
""")
            self._write(root, "src/mobile/build.gradle.kts",
                        "dependencies { implementation(libs.not.in.catalog) }")
            _, errors = collect(root)
            self.assertTrue(any("undefined version.ref" in error for error in errors))
            self.assertTrue(any("unresolved catalog alias" in error for error in errors))

    def test_ignored_external_engine_and_gradle_cache(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self._write(root, "src/gradle/libs.versions.toml", "[versions]\n")
            self._write(root, "src/external/OpenVPNEngine/main/build.gradle.kts",
                        "implementation(libs.missing.engine)")
            self._write(root, "src/.gradle/cache/build.gradle.kts",
                        "implementation(libs.missing.cache)")
            _, errors = collect(root)
            self.assertEqual(errors, [])


if __name__ == "__main__":
    unittest.main()
