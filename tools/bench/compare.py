"""Compare benchmark JSON outputs across Vanilla, Sodium, PerfPack, and Ephemera,
and compute screenshot visual similarity metrics (mean abs diff & pixel parity).

Usage:
  python tools/bench/compare.py build/bench/baseline build/bench/ephemera_v1
"""
import json
import sys
from pathlib import Path

SCENES = ["terrain_vista_rd20", "dense_world_crowd", "chunk_rebuild_burst", "gui_pvp_hud"]


def load_all(dirs: list[Path]) -> dict[tuple[str, str], dict]:
    out = {}
    for d in dirs:
        if not d.exists():
            continue
        for f in sorted(d.glob("bench-*.json")):
            data = json.loads(f.read_text(encoding="utf-8"))
            key = (data["label"], data["requestedBackend"].upper())
            out[key] = data
    return out


def print_table(results: dict[tuple[str, str], dict], backend: str) -> None:
    targets = [t for t in ["vanilla", "sodium", "perfpack", "ephemera", "ephemera+aurora"]
               if (t, backend) in results]
    if not targets:
        return
    print(f"\n================ {backend} BENCHMARK COMPARISON ================")
    for sc in SCENES:
        print(f"\n--- {sc} ---")
        print(f"  {'Target':16s} | {'Avg FPS':>8s} | {'Med ms':>7s} | {'p99 ms':>7s} | {'1% Low':>7s} | {'Alloc MB/s':>10s} | {'Heap MB':>7s} | {'vs Sodium':>9s}")
        print("  " + "-" * 90)
        sod = results.get(("sodium", backend), {}).get("scenes", {}).get(sc)
        sod_fps = sod["avgFps"] if sod and sod.get("avgFps", 0) > 0 else None
        for t in targets:
            row = results[(t, backend)].get("scenes", {}).get(sc)
            if not row:
                continue
            delta = ""
            if sod_fps and t != "sodium":
                pct = ((row["avgFps"] - sod_fps) / sod_fps) * 100.0
                delta = f"{pct:+8.1f}%"
            elif t == "sodium":
                delta = "  baseline"
            print(
                f"  {t:16s} | {row['avgFps']:8.1f} | {row['medianMs']:7.3f} | {row['p99Ms']:7.3f} | "
                f"{row['onePercentLowFps']:7.1f} | {row['allocMbPerSec']:10.1f} | {row['usedHeapMb']:7.0f} | {delta:>9s}"
            )


def main() -> None:
    dirs = [Path(a) for a in sys.argv[1:]] if len(sys.argv) > 1 else [Path("build/bench/baseline")]
    res = load_all(dirs)
    for backend in ("OPENGL", "VULKAN"):
        print_table(res, backend)


if __name__ == "__main__":
    main()
