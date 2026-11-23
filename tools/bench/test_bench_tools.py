"""Unit tests for tools/bench/compare_versions.py fail-closed behavior."""
import json
import tempfile
import unittest
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))
import compare_versions


def write_run(base: Path, subdir: str, filename: str, scenes: dict) -> None:
    d = base / subdir
    d.mkdir(parents=True, exist_ok=True)
    (d / filename).write_text(json.dumps({"scenes": scenes}), encoding="utf-8")


class CompareVersionsTest(unittest.TestCase):
    def test_empty_directory_exits_with_missing_inputs(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            rc = compare_versions.main([tmp])
            self.assertEqual(rc, 2)

    def test_missing_scene_in_263_exits_with_missing_inputs(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            write_run(root, "gl-rep1", "bench-ephemera-opengl.json", {
                "terrain_vista": {"avgFps": 500, "onePercentLowFps": 400},
                "dense_crowd": {"avgFps": 300, "onePercentLowFps": 250},
            })
            write_run(root, "gl-rep1", "bench-ephemera-mc26.3-opengl.json", {
                "terrain_vista": {"avgFps": 510, "onePercentLowFps": 410},
                # dense_crowd missing
            })
            rc = compare_versions.main([str(root), "--backends", "opengl"])
            self.assertEqual(rc, 2)

    def test_complete_matrix_passing_exits_zero(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            for tag, backend in [("gl", "opengl"), ("vk", "vulkan")]:
                for rep in (1, 2):
                    write_run(root, f"{tag}-rep{rep}", f"bench-ephemera-{backend}.json", {
                        "terrain_vista": {"avgFps": 500, "onePercentLowFps": 400},
                    })
                    write_run(root, f"{tag}-rep{rep}", f"bench-ephemera-mc26.3-{backend}.json", {
                        "terrain_vista": {"avgFps": 505, "onePercentLowFps": 402},
                    })
            rc = compare_versions.main([str(root), "--min-repeats", "2"])
            self.assertEqual(rc, 0)

    def test_complete_matrix_with_regression_exits_one(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            write_run(root, "vk-rep1", "bench-ephemera-vulkan.json", {
                "terrain_vista": {"avgFps": 500, "onePercentLowFps": 400},
            })
            write_run(root, "vk-rep1", "bench-ephemera-mc26.3-vulkan.json", {
                "terrain_vista": {"avgFps": 400, "onePercentLowFps": 395},
            })
            rc = compare_versions.main([str(root), "--backends", "vulkan"])
            self.assertEqual(rc, 1)


if __name__ == "__main__":
    unittest.main()
