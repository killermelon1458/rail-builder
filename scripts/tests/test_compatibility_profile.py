"""Offline helper checks; graphical experiments are recorded separately."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
import zipfile

spec = importlib.util.spec_from_file_location("compatibility_profile", Path(__file__).parents[1] / "compatibility/run_profile.py")
profile = importlib.util.module_from_spec(spec)
spec.loader.exec_module(profile)


class CompatibilityProfileTests(unittest.TestCase):
    def test_partial_startup_timeout_and_linkage_are_not_success(self):
        complete = "\n".join(("Initializing Meteor Client", "Initializing Linear Build Suite", "Path Manager: Baritone",
                              "[RailBuilderCompatibility] PASS", "Stopping!"))
        self.assertTrue(profile.evaluate(complete, 0)["startup_passed"])
        self.assertFalse(profile.evaluate(complete, None)["startup_passed"])
        self.assertFalse(profile.evaluate(complete, 1)["startup_passed"])
        self.assertFalse(profile.evaluate(complete.replace("Stopping!", ""), 0)["startup_passed"])
        self.assertFalse(profile.evaluate(complete.replace("[RailBuilderCompatibility] PASS", ""), 0)["startup_passed"])
        for failure in ("NoClassDefFoundError", "NoSuchFieldError", "NoSuchMethodError", "AbstractMethodError", "UnsatisfiedLinkError"):
            self.assertFalse(profile.evaluate(complete + "\n" + failure, 0)["startup_passed"])

    def test_linux_library_rules(self):
        self.assertTrue(profile.allowed({}))
        self.assertTrue(profile.allowed({"rules": [{"action": "allow", "os": {"name": "linux"}}]}))
        self.assertFalse(profile.allowed({"rules": [{"action": "allow", "os": {"name": "windows"}}]}))
        self.assertFalse(profile.allowed({"rules": [{"action": "allow", "os": {"name": "osx"}}]}))
        self.assertFalse(profile.allowed({"rules": [{"action": "allow", "features": {"is_demo_user": True}}]}))
        self.assertFalse(profile.allowed({"rules": [{"action": "allow"}, {"action": "disallow", "os": {"name": "linux"}}]}))

    def test_mod_inventory_uses_embedded_metadata(self):
        with tempfile.TemporaryDirectory() as folder:
            jar = Path(folder) / "misleading-name.jar"
            with zipfile.ZipFile(jar, "w") as output:
                output.writestr("fabric.mod.json", json.dumps({"id": "meteor-client", "version": "1.21.11-37"}))
            info = profile.mod_info(jar)
            self.assertEqual(info["id"], "meteor-client")
            self.assertEqual(info["version"], "1.21.11-37")
            self.assertEqual(len(info["sha256"]), 64)

    def test_corrupt_cached_artifact_is_not_accepted(self):
        from unittest.mock import patch
        with tempfile.TemporaryDirectory() as folder:
            destination = Path(folder) / "bad.jar"
            destination.write_bytes(b"corrupt")
            with patch.object(profile.urllib.request, "urlopen", side_effect=OSError("offline")) as download:
                with self.assertRaises(OSError):
                    profile.fetch("https://example.invalid/library.jar", destination, "0" * 40, Path(folder))
                download.assert_called_once()

    def test_matching_cached_artifact_needs_no_network(self):
        from unittest.mock import patch
        with tempfile.TemporaryDirectory() as folder:
            destination = Path(folder) / "library.jar"
            destination.write_bytes(b"known artifact")
            with patch.object(profile.urllib.request, "urlopen", side_effect=AssertionError("network used")):
                self.assertEqual(profile.fetch("https://example.invalid/library.jar", destination,
                    profile.digest(destination, "sha1"), Path(folder)), destination)


if __name__ == "__main__":
    unittest.main()
