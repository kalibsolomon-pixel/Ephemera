"""Unit tests for tools/bench/compare_versions.py fail-closed behavior."""
import json
import tempfile
import unittest
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))
import compare_versions
import present_trace


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


class PresentTraceTest(unittest.TestCase):
    def test_parses_presentmon_csv_and_correlates_scene_windows_and_vkdiag(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            csv_path = root / "trace.csv"
            # Two displayed frames + one dropped frame in scene1 window
            csv_path.write_text(
                "Application,ProcessID,SwapChainAddress,PresentRuntime,SyncInterval,PresentFlags,AllowsTearing,PresentMode,CPUStartTime,MsBetweenPresents,MsInPresentAPI,MsBetweenDisplayChange,MsUntilRenderComplete,MsUntilDisplayed\n"
                "javaw.exe,1234,0x1,Vulkan,0,0,1,Hardware: Independent Flip,2026-11-23 13:00:01.000000,4.16,0.12,4.16,1.50,5.20\n"
                "javaw.exe,1234,0x1,Vulkan,0,0,1,Hardware: Independent Flip,2026-11-23 13:00:01.004160,4.20,0.15,4.20,1.60,5.40\n"
                "javaw.exe,1234,0x1,Vulkan,0,0,1,Hardware: Independent Flip,2026-11-23 13:00:01.008360,4.18,0.11,0.00,1.40,0.00\n",
                encoding="utf-8",
            )
            rows = present_trace.parse_presentmon_csv(csv_path)
            self.assertEqual(len(rows), 3)
            self.assertIsNotNone(rows[0]["epochMs"])
            start_ms = int(rows[0]["epochMs"]) - 5
            end_ms = int(rows[2]["epochMs"]) + 5

            bench_path = root / "bench-ephemera-vulkan.json"
            bench_path.write_text(
                json.dumps({
                    "label": "ephemera",
                    "requestedBackend": "VULKAN",
                    "fpsCap": 240,
                    "vsync": False,
                    "borderless": True,
                    "scenes": {
                        "chunk_flight_load": {
                            "avgFps": 240.0,
                            "medianMs": 4.16,
                            "p99Ms": 4.30,
                            "maxMs": 4.50,
                            "onePercentLowFps": 232.0,
                            "renderMedianMs": 1.45,
                            "renderP99Ms": 1.90,
                            "lateFramePct": 0.0,
                            "startEpochMs": start_ms,
                            "endEpochMs": end_ms,
                        }
                    },
                }),
                encoding="utf-8",
            )
            log_path = root / "latest.log"
            log_path.write_text(
                "[13:00:01] [Render thread/INFO]: VkDiag [ASYNC]: fps=240.0 frame=4166us | render=1400us acq=45us blit=120us qsub=80us waitSem=15us pres=30us | worker[sub=75us pres=25us acq=40us]\n",
                encoding="utf-8",
            )
            out_path = root / "presentation.json"
            report = present_trace.correlate_run(bench_path, csv_path, log_path, out_path)
            self.assertTrue(out_path.is_file())
            ov = report["overallPresentation"]
            self.assertEqual(ov["presentCount"], 3)
            self.assertEqual(ov["displayedCount"], 2)
            self.assertEqual(ov["droppedCount"], 1)
            self.assertEqual(ov["presentModes"], {"Hardware: Independent Flip": 3})
            self.assertGreater(ov["displayInterval"]["avgFps"], 230.0)
            self.assertEqual(report["scenes"]["chunk_flight_load"]["presentation"]["displayedCount"], 2)
            self.assertEqual(report["vkDiag"]["samples"], 1)
            self.assertEqual(report["vkDiag"]["avgAcquireUs"], 45.0)
            self.assertEqual(report["vkDiag"]["avgWaitSemUs"], 15.0)


if __name__ == "__main__":
    unittest.main()
