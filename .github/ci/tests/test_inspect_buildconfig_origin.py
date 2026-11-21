import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / "inspect-buildconfig-origin.py"
CANON = "https://openvpngateclient.com"
PKG = "release/com/yahorzabotsin/openvpnclientgate/core"


def java(*primary, extra=""):
    lines = ['  public static final String PRIMARY_SERVERS_URL = "%s";' % v for v in primary]
    return ("package com.yahorzabotsin.openvpnclientgate.core;\npublic final class BuildConfig {\n"
            + "\n".join(lines) + "\n" + extra + "}\n")


class InspectBuildConfigOriginTests(unittest.TestCase):
    def run_helper(self, files, expected=CANON):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            for rel, text in files.items():
                p = root / rel
                p.parent.mkdir(parents=True, exist_ok=True)
                p.write_text(text, encoding="utf-8")
            return subprocess.run([sys.executable, "-I", str(SCRIPT), expected, str(root)],
                                  capture_output=True, text=True)

    def test_match_passes_and_prints_value(self):
        r = self.run_helper({PKG + "/BuildConfig.java": java(CANON)})
        self.assertEqual(r.returncode, 0, r.stdout)
        self.assertIn(CANON, r.stdout)

    def test_expected_trailing_slash_is_normalized(self):
        r = self.run_helper({PKG + "/BuildConfig.java": java(CANON)}, CANON + "/")
        self.assertEqual(r.returncode, 0, r.stdout)

    def test_compiled_trailing_slash_is_normalized(self):
        r = self.run_helper({PKG + "/BuildConfig.java": java(CANON + "/")})
        self.assertEqual(r.returncode, 0, r.stdout)

    def test_mismatch_fails_and_prints_found_value(self):
        r = self.run_helper({PKG + "/BuildConfig.java": java("https://example.com")})
        self.assertEqual(r.returncode, 1)
        self.assertIn("https://example.com", r.stdout)

    def test_about_only_canonical_fails(self):
        extra = '  public static final String WEBSITE = "%s";\n' % CANON
        r = self.run_helper({PKG + "/BuildConfig.java": java("https://example.com", extra=extra)})
        self.assertEqual(r.returncode, 1)

    def test_missing_file_fails_closed(self):
        self.assertEqual(self.run_helper({}).returncode, 1)

    def test_missing_field_fails_closed(self):
        r = self.run_helper({PKG + "/BuildConfig.java": java()})
        self.assertEqual(r.returncode, 1)

    def test_two_declarations_are_ambiguous(self):
        r = self.run_helper({PKG + "/BuildConfig.java": java(CANON, CANON)})
        self.assertEqual(r.returncode, 1)

    def test_two_files_are_ambiguous(self):
        r = self.run_helper({PKG + "/BuildConfig.java": java(CANON),
                             "other/core/BuildConfig.java": java(CANON)})
        self.assertEqual(r.returncode, 1)

    def test_invalid_expected_origin_is_usage_error(self):
        r = self.run_helper({PKG + "/BuildConfig.java": java(CANON)}, CANON + ":8443")
        self.assertEqual(r.returncode, 2)


if __name__ == "__main__":
    unittest.main()
