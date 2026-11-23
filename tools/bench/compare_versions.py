"""26.2 vs 26.3 verdict per backend/scene. Usage: compare_versions.py <dir with {gl,vk}-rep*/ runs>

Margin of error per scene/metric = the larger relative spread between the repeated runs of
Ephemera 26.2 and of Ephemera 26.3 (|max-min| / mean), floored at 3%. 26.3 passes a metric when
mean(26.3) >= mean(26.2) * (1 - margin).

Exits:
  0: all requested backends, repeats, and scenes present and passing
  1: complete matrix with one or more metric regressions
  2: missing required inputs (backends, repeats, or scenes)
"""
import argparse
import json
import statistics
import sys
from pathlib import Path

FLOOR = 0.03
BACKEND_TAGS = {
    "opengl": "gl",
    "vulkan": "vk",
}


def load(root: Path, pattern: str) -> list[dict]:
    out = []
    for f in sorted(root.glob(pattern)):
        data = json.loads(f.read_text(encoding="utf-8"))
        scenes = data.get("scenes")
        if isinstance(scenes, dict):
            out.append(scenes)
    return out


def spread(vals: list[float]) -> float:
    if len(vals) < 2:
        return 0.0
    m = statistics.mean(vals)
    return (max(vals) - min(vals)) / m if m else 0.0


def collect_scenes(runs_a: list[dict], runs_b: list[dict], required_scenes: list[str] | None) -> list[str]:
    ordered: list[str] = []
    seen: set[str] = set()
    for scene in (required_scenes or []):
        if scene not in seen:
            ordered.append(scene)
            seen.add(scene)
    for run in runs_a + runs_b:
        for scene in run.keys():
            if scene not in seen:
                ordered.append(scene)
                seen.add(scene)
    return ordered


def compare_dir(
    root: Path,
    backends: list[str] | None = None,
    min_repeats: int = 1,
    required_scenes: list[str] | None = None,
) -> tuple[int, list[str]]:
    selected_backends = [b.lower() for b in (backends or ["opengl", "vulkan"])]
    missing_issues: list[str] = []
    metric_failures = 0

    for backend in selected_backends:
        tag = BACKEND_TAGS.get(backend)
        if tag is None:
            missing_issues.append(f"unknown backend '{backend}'")
            continue
        e262 = load(root, f"{tag}-rep*/bench-ephemera-{backend}.json")
        e263 = load(root, f"{tag}-rep*/bench-ephemera-mc26.3-{backend}.json")
        v262 = load(root, f"{tag}-rep*/bench-vanilla-{backend}.json")
        v263 = load(root, f"{tag}-rep*/bench-vanilla-mc26.3-{backend}.json")
        if len(e262) < min_repeats or len(e263) < min_repeats:
            msg = f"{backend}: insufficient runs (26.2={len(e262)}, 26.3={len(e263)}, required>={min_repeats})"
            print(f"## {msg}")
            missing_issues.append(msg)
            continue

        scenes = collect_scenes(e262, e263, required_scenes)
        if not scenes:
            msg = f"{backend}: no scenes found in benchmark results"
            print(f"## {msg}")
            missing_issues.append(msg)
            continue

        print(f"## {backend.upper()}  (runs: eph26.2={len(e262)} eph26.3={len(e263)} van26.2={len(v262)} van26.3={len(v263)})")
        print(f"{'scene':20s} {'metric':6s} {'eph26.2':>8s} {'eph26.3':>8s} {'delta':>7s} {'margin':>6s} {'van26.2':>8s} {'van26.3':>8s}  verdict")
        for scene in scenes:
            for metric, key in [("avg", "avgFps"), ("1%L", "onePercentLowFps")]:
                a = [r[scene][key] for r in e262 if scene in r and key in r[scene]]
                b = [r[scene][key] for r in e263 if scene in r and key in r[scene]]
                if len(a) < len(e262) or len(b) < len(e263) or not a or not b:
                    msg = f"{backend}/{scene}/{metric}: missing data (26.2={len(a)}/{len(e262)}, 26.3={len(b)}/{len(e263)})"
                    print(f"{scene:20s} {metric:6s} {'MISSING':>8s} {'MISSING':>8s} {'':>7s} {'':>6s} {'':>8s} {'':>8s}  MISSING")
                    missing_issues.append(msg)
                    continue
                ma, mb = statistics.mean(a), statistics.mean(b)
                if ma <= 0:
                    msg = f"{backend}/{scene}/{metric}: invalid baseline mean ({ma})"
                    print(f"{scene:20s} {metric:6s} {ma:8.0f} {mb:8.0f} {'n/a':>7s} {'n/a':>6s} {'':>8s} {'':>8s}  MISSING")
                    missing_issues.append(msg)
                    continue
                margin = max(FLOOR, spread(a), spread(b))
                delta = mb / ma - 1
                ok = mb >= ma * (1 - margin)
                metric_failures += 0 if ok else 1
                va_vals = [r[scene][key] for r in v262 if scene in r and key in r[scene]]
                vb_vals = [r[scene][key] for r in v263 if scene in r and key in r[scene]]
                va = statistics.mean(va_vals) if va_vals else float("nan")
                vb = statistics.mean(vb_vals) if vb_vals else float("nan")
                print(f"{scene:20s} {metric:6s} {ma:8.0f} {mb:8.0f} {delta:+7.1%} {margin:6.1%} {va:8.0f} {vb:8.0f}  {'PASS' if ok else 'FAIL'}")
        print()

    print(f"MISSING INPUTS: {len(missing_issues)}")
    print(f"METRIC FAILURES: {metric_failures}")
    print(f"FAILURES: {metric_failures}")
    return metric_failures, missing_issues


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description="Compare Ephemera 26.2 vs 26.3 benchmark results.")
    ap.add_argument("root", type=Path, help="Directory containing {gl,vk}-rep*/ benchmark runs")
    ap.add_argument("--backends", default="opengl,vulkan", help="Comma-separated required backends")
    ap.add_argument("--min-repeats", type=int, default=1, help="Minimum repeats required per version/backend")
    ap.add_argument("--scenes", default="", help="Optional comma-separated list of required scene names")
    args = ap.parse_args(argv)

    backends = [b.strip().lower() for b in args.backends.split(",") if b.strip()]
    scenes = [s.strip() for s in args.scenes.split(",") if s.strip()] or None
    metric_failures, missing_issues = compare_dir(
        args.root,
        backends=backends,
        min_repeats=max(1, args.min_repeats),
        required_scenes=scenes,
    )
    if missing_issues:
        return 2
    if metric_failures > 0:
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
