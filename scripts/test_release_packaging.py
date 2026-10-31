import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
import zipfile

import yaml


spec = importlib.util.spec_from_file_location(
    "validate_plugin_package", Path(__file__).with_name("validate-plugin-package.py")
)
validator = importlib.util.module_from_spec(spec)
spec.loader.exec_module(validator)


class PluginPackageTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.lock = {
            "apiVersion": "4.0.0",
            "plugins": [{"id": "demo", "version": "1.0.0", "tag": "v1.0.0", "asset": "demo.jar"}],
        }

    def validate(self, **overrides):
        descriptor = {"id": "demo", "version": "1.0.0", "apiVersion": "4.0.0", **overrides}
        with zipfile.ZipFile(self.root / "demo.jar", "w") as jar:
            jar.writestr("plugin.yml", yaml.safe_dump(descriptor))
        lock_path = self.root / "lock.json"
        lock_path.write_text(json.dumps(self.lock), encoding="utf-8")
        return validator.validate_package(lock_path, self.root)

    def test_matching_package(self):
        self.assertEqual(1, self.validate())

    def test_rejects_wrong_identity_version_or_api(self):
        for field, value in (("id", "other"), ("version", "0.9.0"), ("apiVersion", "3.0.0")):
            with self.subTest(field=field), self.assertRaises(ValueError):
                self.validate(**{field: value})

    def test_rejects_duplicate_plugin(self):
        self.lock["plugins"] *= 2
        with self.assertRaisesRegex(ValueError, "Duplicate"):
            self.validate()

    def test_rejects_wrong_tag(self):
        self.lock["plugins"][0]["tag"] = "v0.9.0"
        with self.assertRaisesRegex(ValueError, "tag/version"):
            self.validate()

    def test_rejects_unlisted_jar(self):
        (self.root / "stale.jar").write_bytes(b"stale")
        with self.assertRaisesRegex(ValueError, "Unexpected"):
            self.validate()

    def test_rejects_unsafe_asset_path(self):
        self.lock["plugins"][0]["asset"] = "../demo.jar"
        with self.assertRaisesRegex(ValueError, "Invalid plugin asset"):
            self.validate()

    def test_rejects_missing_or_oversized_descriptor(self):
        self.validate()
        for text in (None, "x" * 65537):
            with zipfile.ZipFile(self.root / "demo.jar", "w") as jar:
                if text is not None:
                    jar.writestr("plugin.yml", text)
            with self.subTest(text_size=len(text or "")), self.assertRaises(ValueError):
                validator.validate_package(self.root / "lock.json", self.root)


if __name__ == "__main__":
    unittest.main()
