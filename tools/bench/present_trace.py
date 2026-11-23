"""Presentation-level frame pacing analyzer & PresentMon correlator for Ephemera benchmarks.

Captures or parses PresentMon CSV traces (v1 and v2 schemas), extracts displayed-frame intervals,
dropped frames, present modes (Hardware: Independent Flip vs Composed: Flip), GPU render duration,
and Present API waits, then correlates them with BenchProbe scene timestamps and Ephemera VkDiag
logs (acquire, blit, queue submit, semaphore wait, present).

CLI usage:
  python tools/bench/present_trace.py --bench bench-ephemera-vulkan.json --presentmon-csv trace.csv --log latest.log
"""
from __future__ import annotations

import argparse
import csv
import datetime as dt
import json
import math
import re
import shutil
import sys
from pathlib import Path
from typing import Any


PRESENTMON_SEARCH_PATHS = [
    Path(r"C:\Program Files\AMD\CNext\CNext\PresentMon-x64.exe"),
    Path(r"C:\Program Files\Intel\PresentMon\PresentMon.exe"),
    Path(r"C:\Program Files\Intel\PresentMon\PresentMon-x64.exe"),
    Path(r"C:\Program Files (x86)\Intel\PresentMon\PresentMon.exe"),
]

VK_DIAG_RE = re.compile(
    r"VkDiag\s+\[([^\]]+)\]:\s+"
    r"fps=([0-9.]+)\s+frame=([0-9.]+)us\s+\|\s+"
    r"render=([0-9.]+)us\s+acq=([0-9.]+)us\s+blit=([0-9.]+)us\s+"
    r"qsub=([0-9.]+)us\s+waitSem=([0-9.]+)us\s+pres=([0-9.]+)us\s+\|\s+"
    r"worker\[sub=([0-9.]+)us\s+pres=([0-9.]+)us\s+acq=([0-9.]+)us\]"
)


def find_presentmon(hint: str = "auto") -> Path | None:
    """Finds a usable PresentMon executable, or returns None when unavailable."""
    if not hint:
        return None
    if hint.lower() != "auto":
        p = Path(hint)
        return p if p.is_file() else None
    for name in ("PresentMon-x64.exe", "PresentMon.exe", "PresentMon"):
        found = shutil.which(name)
        if found:
            return Path(found)
    for candidate in PRESENTMON_SEARCH_PATHS:
        if candidate.is_file():
            return candidate
    return None


def build_presentmon_command(exe: Path, pid: int, output_csv: Path) -> list[str]:
    """Builds a scoped PresentMon command targeting a single game PID."""
    return [
        str(exe),
        "--process_id",
        str(pid),
        "--output_file",
        str(output_csv),
        "--no_console_stats",
        "--date_time",
    ]


def _parse_float(value: str | None) -> float | None:
    if value is None:
        return None
    s = value.strip()
    if not s or s.upper() == "NA":
        return None
    try:
        v = float(s)
        return v if math.isfinite(v) else None
    except ValueError:
        return None


def _parse_epoch_ms(raw: str | None) -> float | None:
    """Parses PresentMon --date_time ISO-like timestamps into local epoch milliseconds."""
    if not raw:
        return None
    s = raw.strip()
    if not s:
        return None
    # PresentMon outputs e.g. "2026-11-23 13:45:12.123456789" or ISO 8601
    s = s.replace(" ", "T")
    if "." in s:
        head, frac = s.split(".", 1)
        # Strip trailing timezone if present, clamp subseconds to microseconds
        tz = ""
        for sep in ("Z", "+", "-"):
            idx = frac.find(sep)
            if idx >= 0:
                tz = frac[idx:]
                frac = frac[:idx]
                break
        frac = (frac[:6]).ljust(6, "0")
        s = f"{head}.{frac}{tz}"
    try:
        parsed = dt.datetime.fromisoformat(s.replace("Z", "+00:00"))
        return parsed.timestamp() * 1000.0
    except ValueError:
        return None


def _percentile(sorted_vals: list[float], p: float) -> float:
    if not sorted_vals:
        return 0.0
    idx = min(len(sorted_vals) - 1, max(0, math.ceil(p * len(sorted_vals)) - 1))
    return sorted_vals[idx]


def _tail_fps(sorted_ms: list[float], frac: float) -> float:
    if not sorted_ms:
        return 0.0
    n = len(sorted_ms)
    k = max(1, math.ceil(n * frac))
    tail_sum = sum(sorted_ms[n - k :])
    return (k * 1000.0) / tail_sum if tail_sum > 0.0 else 0.0


def _summarize_ms(values: list[float]) -> dict[str, float]:
    if not values:
        return {
            "samples": 0,
            "avgMs": 0.0,
            "medianMs": 0.0,
            "p95Ms": 0.0,
            "p99Ms": 0.0,
            "maxMs": 0.0,
            "avgFps": 0.0,
            "onePercentLowFps": 0.0,
            "pointOnePercentLowFps": 0.0,
        }
    s = sorted(values)
    total = sum(s)
    n = len(s)
    avg_ms = total / n
    return {
        "samples": n,
        "avgMs": round(avg_ms, 3),
        "medianMs": round(_percentile(s, 0.50), 3),
        "p95Ms": round(_percentile(s, 0.95), 3),
        "p99Ms": round(_percentile(s, 0.99), 3),
        "maxMs": round(s[-1], 3),
        "avgFps": round(1000.0 / avg_ms, 2) if avg_ms > 0.0 else 0.0,
        "onePercentLowFps": round(_tail_fps(s, 0.01), 2),
        "pointOnePercentLowFps": round(_tail_fps(s, 0.001), 2),
    }


def parse_presentmon_csv(csv_path: Path) -> list[dict[str, Any]]:
    """Parses a PresentMon v1 or v2 CSV file into normalized per-present records."""
    rows: list[dict[str, Any]] = []
    if not csv_path.is_file():
        return rows
    with csv_path.open("r", encoding="utf-8", errors="ignore", newline="") as f:
        reader = csv.DictReader(f)
        if not reader.fieldnames:
            return rows
        fields = {name.strip(): name for name in reader.fieldnames}

        def get(row: dict[str, str], *candidates: str) -> str | None:
            for c in candidates:
                key = fields.get(c)
                if key is not None:
                    val = row.get(key)
                    if val is not None and val.strip() != "":
                        return val
            return None

        for raw in reader:
            ms_between_presents = _parse_float(get(raw, "MsBetweenPresents", "FrameTime"))
            ms_between_display = _parse_float(get(raw, "MsBetweenDisplayChange", "MsBetweenDisplayChanges", "DisplayedTime"))
            ms_in_present_api = _parse_float(get(raw, "MsInPresentAPI", "PresentAPITime", "CPUWait"))
            ms_render_complete = _parse_float(
                get(raw, "MsUntilRenderComplete", "GPUTime", "MsGPUActive", "GPUBusy", "GPUDuration")
            )
            ms_until_displayed = _parse_float(get(raw, "MsUntilDisplayed", "DisplayLatency"))
            dropped_raw = get(raw, "Dropped", "WasBatched")
            if dropped_raw is not None:
                dropped: bool | None = dropped_raw.strip().lower() in ("1", "true", "yes")
            elif ms_between_display is not None:
                dropped = ms_between_display <= 0.0
            else:
                # Non-elevated or windowed Composed: Flip traces emit NA for DisplayedTime/DisplayLatency.
                dropped = None
            present_mode = (get(raw, "PresentMode") or "Unknown").strip()
            time_sec = _parse_float(get(raw, "TimeInSeconds", "CPUStartTime"))
            epoch_ms = _parse_epoch_ms(get(raw, "CPUStartDateTime", "TimeInSeconds", "CPUStartTime", "DateTime"))
            rows.append(
                {
                    "timeSec": time_sec,
                    "epochMs": epoch_ms,
                    "msBetweenPresents": ms_between_presents,
                    "msBetweenDisplayChange": ms_between_display,
                    "msInPresentApi": ms_in_present_api,
                    "msUntilRenderComplete": ms_render_complete,
                    "msUntilDisplayed": ms_until_displayed,
                    "dropped": dropped,
                    "presentMode": present_mode,
                }
            )
    return rows


def summarize_present_rows(rows: list[dict[str, Any]], target_fps: int = 0) -> dict[str, Any]:
    """Computes displayed-frame pacing, drop rate, latency, and PresentMode distribution."""
    total = len(rows)
    presents = [r["msBetweenPresents"] for r in rows if r["msBetweenPresents"] is not None and r["msBetweenPresents"] > 0.0]
    display_tracking_available = any(r["dropped"] is not None for r in rows)
    displays = [
        r["msBetweenDisplayChange"]
        for r in rows
        if r["dropped"] is False and r["msBetweenDisplayChange"] is not None and r["msBetweenDisplayChange"] > 0.0
    ]
    present_api = [r["msInPresentApi"] for r in rows if r["msInPresentApi"] is not None and r["msInPresentApi"] >= 0.0]
    gpu_complete = [
        r["msUntilRenderComplete"]
        for r in rows
        if r["msUntilRenderComplete"] is not None and r["msUntilRenderComplete"] >= 0.0
    ]
    displayed_lat = [
        r["msUntilDisplayed"]
        for r in rows
        if r["dropped"] is False and r["msUntilDisplayed"] is not None and r["msUntilDisplayed"] >= 0.0
    ]
    dropped = sum(1 for r in rows if r["dropped"] is True)
    modes: dict[str, int] = {}
    for r in rows:
        m = r["presentMode"]
        modes[m] = modes.get(m, 0) + 1

    out: dict[str, Any] = {
        "presentCount": total,
        "displayTrackingAvailable": display_tracking_available,
        "displayedCount": (total - dropped) if display_tracking_available else 0,
        "droppedCount": dropped if display_tracking_available else 0,
        "droppedPct": round((dropped * 100.0) / total, 2) if (total > 0 and display_tracking_available) else 0.0,
        "presentInterval": _summarize_ms(presents),
        "displayInterval": _summarize_ms(displays),
        "presentApiMs": {
            "median": round(_percentile(sorted(present_api), 0.50), 3) if present_api else 0.0,
            "p99": round(_percentile(sorted(present_api), 0.99), 3) if present_api else 0.0,
        },
        "gpuRenderCompleteMs": {
            "median": round(_percentile(sorted(gpu_complete), 0.50), 3) if gpu_complete else 0.0,
            "p99": round(_percentile(sorted(gpu_complete), 0.99), 3) if gpu_complete else 0.0,
        },
        "displayLatencyMs": {
            "median": round(_percentile(sorted(displayed_lat), 0.50), 3) if displayed_lat else 0.0,
            "p99": round(_percentile(sorted(displayed_lat), 0.99), 3) if displayed_lat else 0.0,
        },
        "presentModes": modes,
    }
    pacing_series = displays if displays else presents
    if target_fps > 0 and pacing_series:
        late_ms = (1000.0 / target_fps) * 1.15
        late_disp = sum(1 for d in pacing_series if d > late_ms)
        out["targetFps"] = target_fps
        out["lateDisplayedCount"] = late_disp
        out["lateDisplayedPct"] = round((late_disp * 100.0) / len(pacing_series), 2)
    return out


def parse_vkdiag_log(log_path: Path | None) -> dict[str, Any]:
    """Parses Ephemera VkDiag telemetry lines from a Minecraft latest.log file."""
    if log_path is None or not log_path.is_file():
        return {"samples": 0}
    samples: list[dict[str, float]] = []
    mode = ""
    for line in log_path.read_text(encoding="utf-8", errors="ignore").splitlines():
        m = VK_DIAG_RE.search(line)
        if not m:
            continue
        mode = m.group(1)
        samples.append(
            {
                "fps": float(m.group(2)),
                "frameUs": float(m.group(3)),
                "renderUs": float(m.group(4)),
                "acquireUs": float(m.group(5)),
                "blitUs": float(m.group(6)),
                "queueSubmitUs": float(m.group(7)),
                "waitSemUs": float(m.group(8)),
                "presentUs": float(m.group(9)),
                "workerSubmitUs": float(m.group(10)),
                "workerPresentUs": float(m.group(11)),
                "workerAcquireUs": float(m.group(12)),
            }
        )
    if not samples:
        return {"samples": 0}

    def avg(key: str) -> float:
        return round(sum(s[key] for s in samples) / len(samples), 1)

    return {
        "samples": len(samples),
        "mode": mode,
        "avgFps": avg("fps"),
        "avgFrameUs": avg("frameUs"),
        "avgRenderUs": avg("renderUs"),
        "avgAcquireUs": avg("acquireUs"),
        "avgBlitUs": avg("blitUs"),
        "avgQueueSubmitUs": avg("queueSubmitUs"),
        "avgWaitSemUs": avg("waitSemUs"),
        "avgPresentUs": avg("presentUs"),
        "avgWorkerSubmitUs": avg("workerSubmitUs"),
        "avgWorkerPresentUs": avg("workerPresentUs"),
        "avgWorkerAcquireUs": avg("workerAcquireUs"),
    }


def correlate_run(
    bench_json: Path,
    presentmon_csv: Path | None,
    log_path: Path | None = None,
    out_json: Path | None = None,
) -> dict[str, Any]:
    """Correlates BenchProbe JSON scenes with PresentMon displayed-frame rows and VkDiag logs."""
    bench = json.loads(bench_json.read_text(encoding="utf-8"))
    target_fps = int(bench.get("fpsCap", 0) or 0)
    rows = parse_presentmon_csv(presentmon_csv) if presentmon_csv else []
    has_epoch = any(r["epochMs"] is not None for r in rows)

    correlated_scenes: dict[str, Any] = {}
    for scene_name, sc in bench.get("scenes", {}).items():
        start_ms = sc.get("startEpochMs")
        end_ms = sc.get("endEpochMs")
        if has_epoch and start_ms is not None and end_ms is not None:
            window = [r for r in rows if r["epochMs"] is not None and start_ms <= r["epochMs"] <= end_ms]
        else:
            window = []
        correlated_scenes[scene_name] = {
            "bench": {
                "avgFps": sc.get("avgFps", 0.0),
                "medianMs": sc.get("medianMs", 0.0),
                "p99Ms": sc.get("p99Ms", 0.0),
                "maxMs": sc.get("maxMs", 0.0),
                "onePercentLowFps": sc.get("onePercentLowFps", 0.0),
                "renderMedianMs": sc.get("renderMedianMs", 0.0),
                "renderP99Ms": sc.get("renderP99Ms", 0.0),
                "lateFramePct": sc.get("lateFramePct", 0.0),
            },
            "presentation": summarize_present_rows(window, target_fps),
        }

    report: dict[str, Any] = {
        "label": bench.get("label", ""),
        "backend": bench.get("requestedBackend", ""),
        "fpsCap": target_fps,
        "vsync": bool(bench.get("vsync", False)),
        "borderless": bool(bench.get("borderless", False)),
        "overallPresentation": summarize_present_rows(rows, target_fps),
        "vkDiag": parse_vkdiag_log(log_path),
        "scenes": correlated_scenes,
    }
    if out_json is not None:
        out_json.parent.mkdir(parents=True, exist_ok=True)
        out_json.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    return report


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description="Correlate Ephemera BenchProbe results with PresentMon & VkDiag.")
    ap.add_argument("--bench", required=True, help="Path to bench-<label>-<backend>.json")
    ap.add_argument("--presentmon-csv", default="", help="Path to PresentMon CSV trace")
    ap.add_argument("--log", default="", help="Optional path to Minecraft latest.log for VkDiag correlation")
    ap.add_argument("--out", default="", help="Optional output JSON path")
    args = ap.parse_args(argv)

    bench_path = Path(args.bench)
    csv_path = Path(args.presentmon_csv) if args.presentmon_csv else None
    log_path = Path(args.log) if args.log else None
    out_path = Path(args.out) if args.out else None

    report = correlate_run(bench_path, csv_path, log_path, out_path)
    ov = report["overallPresentation"]
    if ov.get("displayTrackingAvailable", False):
        disp = ov["displayInterval"]
        print(
            f"Presentation [{report['label']} {report['backend']}]: "
            f"displayed={disp['avgFps']:.1f} FPS (med {disp['medianMs']:.2f}ms, p99 {disp['p99Ms']:.2f}ms, 1%L {disp['onePercentLowFps']:.1f}) | "
            f"dropped={ov['droppedCount']}/{ov['presentCount']} ({ov['droppedPct']:.2f}%) | "
            f"modes={ov['presentModes']}"
        )
    else:
        pres = ov["presentInterval"]
        print(
            f"Presentation [{report['label']} {report['backend']}]: "
            f"present={pres['avgFps']:.1f} FPS (med {pres['medianMs']:.2f}ms, p99 {pres['p99Ms']:.2f}ms, 1%L {pres['onePercentLowFps']:.1f}; display tracking N/A) | "
            f"presents={ov['presentCount']} | modes={ov['presentModes']}"
        )
    vk = report["vkDiag"]
    if vk.get("samples", 0) > 0:
        print(
            f"VkDiag ({vk['mode']}): acq={vk['avgAcquireUs']}us qsub={vk['avgQueueSubmitUs']}us "
            f"waitSem={vk['avgWaitSemUs']}us pres={vk['avgPresentUs']}us"
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
