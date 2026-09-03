import importlib.util
import unittest
from pathlib import Path


def module(name):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(name + ".py"))
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


class DeploymentSafetyTests(unittest.TestCase):
    def test_backend_bundle_does_not_overwrite_ai_deployer(self):
        self.assertFalse(Path(__file__).with_name("deploy-ai.sh").exists())

    def test_preserves_running_stopped_pinned_latest_and_foreign_images(self):
        images = [{"Id": str(i), "Created": f"2026-09-{i + 1:02}", "RepoTags": [f"kart-backend:{i}"]} for i in range(10)]
        images += [{"Id": "foreign", "Created": "2020-01-01", "RepoTags": ["postgres:18"]}]
        candidates = module("retain-images").plan(images, [{"Image": "0"}, {"Image": "1"}], ["kart-backend:2"], 5)
        self.assertEqual([item["id"] for item in candidates], ["3", "4"])

    def test_unknown_pin_fails_closed(self):
        with self.assertRaises(ValueError):
            module("retain-images").plan([], [], ["missing"], 5)

    def test_dangling_and_mixed_ownership_are_not_deleted(self):
        images = [{"Id": "a", "Created": "2020", "RepoTags": []},
                  {"Id": "b", "Created": "2020", "RepoTags": ["kart-ai:old", "other:keep"]}]
        self.assertEqual(module("retain-images").plan(images, [], [], 0), [])

    def test_merge_preserves_recovery_settings_and_missing_secret(self):
        result = module("merge-runtime").merged({"RECOVERY": "false", "SECRET": "old", "KEY": "old"}, {"SECRET": "", "KEY": "new"})
        self.assertEqual(result, {"RECOVERY": "false", "SECRET": "old", "KEY": "new"})

    def test_invalid_environment_is_rejected(self):
        with self.assertRaises(ValueError):
            module("merge-runtime").parse("bad key=value")


if __name__ == "__main__":
    unittest.main()
