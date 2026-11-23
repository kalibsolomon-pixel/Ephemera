"""Automated multi-backend benchmark runner comparing Vanilla 26.2, Sodium 0.9.2,
Full Aurora 26.2 Performance Modpack, and Ephemera standalone.

Usage:
  python tools/bench/run_bench.py --targets vanilla,sodium,perfpack,ephemera --backends OPENGL,VULKAN
"""
import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import time
import uuid
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import present_trace

REPO = Path(__file__).resolve().parents[2]
WIN_PS1 = REPO / "tools" / "bench" / "win.ps1"

LOCAL_APP = Path(os.environ["LOCALAPPDATA"])
ROAMING_APP = Path(os.environ["APPDATA"])

AURORA_CLIENT_LIBS = Path(r"C:\Dev\Aurora-Client\build\minecraft-26.2\libs")
AURORA_262_MODS = LOCAL_APP / "com.aurora.launcher.native-preview" / "instances" / "0cb60dcac1354fa0a7e5d5181d5f6090" / "mods"
PANDORA_262_MODS = ROAMING_APP / "PandoraLauncher" / "instances" / "Aurora 26.2 Test" / ".minecraft" / "mods"
LITHIUM_262_MODS = ROAMING_APP / "PandoraLauncher" / "instances" / "26.2" / ".minecraft" / "mods"
DEFAULT_WORLD_SRC = Path(r"C:\Dev\Aurora-Client\build\perf-research-26.2\game\saves\New World")


def default_minecraft() -> str:
    for line in (REPO / "gradle.properties").read_text(encoding="utf-8").splitlines():
        if line.startswith("minecraft_version="):
            return line.split("=", 1)[1].strip()
    raise RuntimeError("minecraft_version missing from gradle.properties")


def find_mod(pattern: str) -> Path:
    for root in (AURORA_CLIENT_LIBS, AURORA_262_MODS, PANDORA_262_MODS, LITHIUM_262_MODS):
        if root.exists():
            matches = sorted(root.glob(pattern))
            if matches:
                return matches[0]
    raise FileNotFoundError(f"Could not find mod matching {pattern}")


MOD_SETS = {
    "vanilla": {
        "disable_ephemera": True,
        "mods": [],
    },
    "sodium": {
        "disable_ephemera": True,
        "mods": ["sodium-fabric-0.9.2+mc26.2.jar"],
    },
    "perfpack": {
        "disable_ephemera": True,
        "mods": [
            "sodium-fabric-0.9.2+mc26.2.jar",
            "lithium-fabric-0.25.3+mc26.2.jar",
            "ImmediatelyFast-Fabric-1.16.5+26.2.jar",
            "entityculling-fabric-1.12.1-mc26.2.jar",
            "moreculling-fabric-26.2-1.8.2.jar",
            "cloth-config-26.2.155.jar",
            "ferritecore-9.0.0-fabric.jar",
            "BadOptimizations-2.4.1-26.2-fabric.jar",
            "particle_core-0.3.3+fix1+26.2.jar",
            "fzzy_config-0.7.7+26.2.jar",
            "fabric-language-kotlin-1.14.2+kotlin.2.4.21.jar",
            "krypton-0.3.1.jar",
            "ScalableLux-fabric-0.3.0-alpha.0.3-all.jar",
            "rrls-5.2.8+mc.26.2.jar",
        ],
    },
    "perfpack-vk": {
        "disable_ephemera": True,
        "mods": [
            "sodium-fabric-0.9.2+mc26.2.jar",
            "lithium-fabric-0.25.3+mc26.2.jar",
            "ImmediatelyFast-Fabric-1.16.5+26.2.jar",
            "entityculling-fabric-1.12.1-mc26.2.jar",
            "moreculling-fabric-26.2-1.8.2.jar",
            "cloth-config-26.2.155.jar",
            "ferritecore-9.0.0-fabric.jar",
            "BadOptimizations-2.4.1-26.2-fabric.jar",
            "particle_core-0.3.3+fix1+26.2.jar",
            "fzzy_config-0.7.7+26.2.jar",
            "fabric-language-kotlin-1.14.2+kotlin.2.4.21.jar",
            "krypton-0.3.1.jar",
            "ScalableLux-fabric-0.3.0-alpha.0.3-all.jar",
        ],
    },
    "ephemera": {
        "disable_ephemera": False,
        "mods": [],
    },
    "ephemera+aurora": {
        "disable_ephemera": False,
        "mods": ["aurora-3.6.0-mc26.2.jar"],
    },
    "ephemera+aurora37": {
        "disable_ephemera": False,
        "mods": ["aurora-3.7.0-mc26.2.jar"],
    },
    "ephemera+spark+ca": {
        "disable_ephemera": False,
        "mods": [
            "spark-1.10.187-fabric.jar",
            "CrashAssistant-fabric-26.2-26.3-1.11.14.jar",
        ],
    },
    "compat-aurora-pvp": {
        "disable_ephemera": False,
        "mods": [
            "aurora-3.7.0-mc26.2.jar",
            "entity_model_features-3.3.12-26.2-fabric.jar",
            "entity_texture_features-7.2.7-26.2-fabric.jar",
            "continuity-3.0.1+26.2.jar",
            "notenoughanimations-fabric-1.12.7-mc26.2.jar",
            "skinlayers3d-fabric-1.11.4-mc26.2.jar",
            "waveycapes-fabric-1.11.3-mc26.2.jar",
            "voicechat-fabric-2.6.22+26.2.jar",
            "modmenu-20.0.3.jar",
            "Controlling-fabric-26.2-26.2.4.jar",
            "Searchables-fabric-26.2-1.0.1.jar",
            "PacketFixer-fabric-3.3.6.jar",
            "spark-1.10.187-fabric.jar",
            "CrashAssistant-fabric-26.2-26.3-1.11.14.jar",
        ],
    },
    "compat-popular-mods": {
        "disable_ephemera": False,
        "mods": [
            "entity_model_features-3.3.12-26.2-fabric.jar",
            "entity_texture_features-7.2.7-26.2-fabric.jar",
            "continuity-3.0.1+26.2.jar",
            "polytone-26.2-6.7.0-fabric.jar",
            "lambdynamiclights-4.12.4+26.2.jar",
            "BetterGrassify-1.8.7+fabric.26.2.jar",
            "animatica-0.6.3+26.2.jar",
            "skyboxify-3.3.1+26.2-fabric.jar",
            "dynamic-fps-3.11.9+minecraft-26.2.0-fabric.jar",
            "fast-ip-ping-v1.0.12-mc26.2.jar",
            "Ixeris-4.6.5+26.2-fabric.jar",
            "debugify-26.2.0.0.jar",
            "fastquit-3.1.5+mc26.2.jar",
            "cloth-config-26.2.155.jar",
            "yet_another_config_lib_v3-3.9.5+26.2-fabric.jar",
            "fabric-language-kotlin-1.14.2+kotlin.2.4.21.jar",
            "spark-1.10.187-fabric.jar",
            "CrashAssistant-fabric-26.2-26.3-1.11.14.jar",
        ],
    },
    "compat-sodium-iris": {
        "disable_ephemera": False,
        "mods": [
            "sodium-fabric-0.9.2+mc26.2.jar",
            "iris-fabric-1.11.4+mc26.2.jar",
            "spark-1.10.187-fabric.jar",
            "CrashAssistant-fabric-26.2-26.3-1.11.14.jar",
        ],
    },
    "compat-perfpack-coexist": {
        "disable_ephemera": False,
        "mods": [
            "sodium-fabric-0.9.2+mc26.2.jar",
            "lithium-fabric-0.25.3+mc26.2.jar",
            "ImmediatelyFast-Fabric-1.16.5+26.2.jar",
            "entityculling-fabric-1.12.1-mc26.2.jar",
            "moreculling-fabric-26.2-1.8.2.jar",
            "cloth-config-26.2.155.jar",
            "ferritecore-9.0.0-fabric.jar",
            "BadOptimizations-2.4.1-26.2-fabric.jar",
            "particle_core-0.3.3+fix1+26.2.jar",
            "fzzy_config-0.7.7+26.2.jar",
            "fabric-language-kotlin-1.14.2+kotlin.2.4.21.jar",
            "krypton-0.3.1.jar",
            "ScalableLux-fabric-0.3.0-alpha.0.3-all.jar",
            "spark-1.10.187-fabric.jar",
            "CrashAssistant-fabric-26.2-26.3-1.11.14.jar",
        ],
    },
}


def ps(*args: str) -> tuple[int, str]:
    proc = subprocess.run(
        ["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", *args],
        capture_output=True,
        text=True,
    )
    return proc.returncode, proc.stdout.strip()


def prepare_run_dir(run_dir: Path, mod_patterns: list[str], backend: str, render_distance: int = 20, fps_cap: int = 0, vsync: bool = False) -> None:
    if run_dir.exists():
        shutil.rmtree(run_dir, ignore_errors=True)
    run_dir.mkdir(parents=True, exist_ok=True)
    saves_dst = run_dir / "saves" / "New World"
    shutil.copytree(DEFAULT_WORLD_SRC, saves_dst)
    mods_dst = run_dir / "mods"
    mods_dst.mkdir(parents=True, exist_ok=True)
    for pat in mod_patterns:
        src = find_mod(pat)
        shutil.copy2(src, mods_dst / src.name)
    # Clean standardized options.txt
    effective_fps = max(10, min(260, fps_cap)) if fps_cap > 0 else 260
    opts = [
        "version:4792",
        f"renderDistance:{render_distance}",
        "simulationDistance:12",
        f"enableVsync:{'true' if vsync else 'false'}",
        f"maxFps:{effective_fps}",
        "inactivityFpsLimit:\"minimized\"",
        "fullscreen:false",
        "guiScale:3",
        "ao:true",
        "biomeBlendRadius:2",
        "mipmapLevels:4",
        "particles:0",
        "renderClouds:\"true\"",
        "entityDistanceScaling:1.0",
        "soundCategory_master:0.0",
        "pauseOnLostFocus:false",
        "tutorialStep:none",
        "onboardAccessibility:false",
        "skipMultiplayerWarning:true",
        f"preferredGraphicsBackend:\"{'vulkan' if backend == 'VULKAN' else 'opengl'}\"",
    ]
    (run_dir / "options.txt").write_text("\n".join(opts) + "\n", encoding="utf-8")


def run_one(target: str, backend: str, out_dir: Path, duration_sec: int, jfr: bool, extra_jvm: str = "", render_distance: int = 20, suite: str = "default", spark: bool = False, minecraft: str | None = None, fps_cap: int = 0, vsync: bool = False, borderless: bool = False, presentmon: str = "") -> dict | None:
    spec = MOD_SETS[target]
    minecraft = minecraft or default_minecraft()
    # Non-default Minecraft versions get their own run dir and a label suffix.
    mc_suffix = "" if minecraft == default_minecraft() else f"-mc{minecraft}"
    label = f"{target}{mc_suffix}"
    run_dir = REPO / "build" / "bench" / f"game-{label}-{backend.lower()}"
    prepare_run_dir(run_dir, spec["mods"], backend, render_distance, fps_cap=fps_cap, vsync=vsync)
    out_dir.mkdir(parents=True, exist_ok=True)

    result_file = out_dir / f"bench-{label}-{backend.lower()}.json"
    if result_file.exists():
        result_file.unlink()
    pm_exe = present_trace.find_presentmon(presentmon) if presentmon else None
    pm_csv = out_dir / f"presentmon-{label}-{backend.lower()}.csv"
    if pm_csv.exists():
        pm_csv.unlink()

    run_id = uuid.uuid4().hex
    cmd = [
        "cmd", "/c", str(REPO / "gradlew.bat"),
        "--no-daemon", "--console=plain",
        "runClient",
        f"-Pephemera.runDir={run_dir}",
        f"-Pephemera.benchOut={out_dir}",
        f"-Pephemera.minecraft={minecraft}",
        f"-Pephemera.benchLabel={label}",
        f"-Pephemera.benchBackend={backend}",
        f"-Pephemera.benchDisableEphemera={'true' if spec['disable_ephemera'] else 'false'}",
        f"-Pephemera.benchJfr={'true' if jfr else 'false'}",
        f"-Pephemera.benchSpark={'true' if spark else 'false'}",
        f"-Pephemera.benchDurationSec={duration_sec}",
        f"-Pephemera.benchRd={render_distance}",
        f"-Pephemera.benchSuite={suite}",
        f"-Pephemera.benchFpsCap={fps_cap}",
        f"-Pephemera.benchVsync={'true' if vsync else 'false'}",
        f"-Pephemera.benchBorderless={'true' if borderless else 'false'}",
        f"-Pephemera.benchRunId={run_id}",
        "-I", str(REPO / "tools" / "bench" / "bench.init.gradle"),
    ]
    if extra_jvm:
        cmd.append(f"-Pephemera.extraJvmArgs={extra_jvm}")
    gradle_log = out_dir / f"gradle-{label}-{backend.lower()}.log"
    print(f"\n=== BENCHMARK: {label} [{backend}] (suite={suite}, spark={spark}, cap={fps_cap}, vsync={vsync}) ===", flush=True)
    pm_proc: subprocess.Popen | None = None
    with open(gradle_log, "w", encoding="utf-8") as lf:
        proc = subprocess.Popen(cmd, cwd=REPO, stdout=lf, stderr=subprocess.STDOUT)
        started = time.time()
        moved = False
        latest_log = run_dir / "logs" / "latest.log"
        max_wait = 360 if suite == "all" else 240
        while time.time() - started < max_wait:
            if proc.poll() is not None:
                break
            if not moved:
                rc, res = ps("-File", str(WIN_PS1), "-Action", "move", "-ParentPid", str(proc.pid), "-RunId", run_id)
                if rc == 0 and "MOVED" in res:
                    moved = True
                    print(f"  Window placed on secondary portrait monitor ({res}).", flush=True)
                    if pm_exe is not None and pm_proc is None:
                        m_pid = re.search(r"pid=(\d+)", res)
                        if m_pid:
                            game_pid = int(m_pid.group(1))
                            pm_cmd = present_trace.build_presentmon_command(pm_exe, game_pid, pm_csv)
                            try:
                                pm_proc = subprocess.Popen(pm_cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                                print(f"  Attached PresentMon ({pm_exe.name}) to PID {game_pid}.", flush=True)
                            except OSError as exc:
                                print(f"  Warning: could not launch PresentMon ({exc}); continuing without display trace.", flush=True)
            if result_file.exists():
                time.sleep(2)
                break
            if latest_log.exists():
                try:
                    txt = latest_log.read_text(encoding="utf-8", errors="ignore")
                    if "Incompatible mods found!" in txt or "Failed to launch" in txt:
                        print("  Detected Fabric startup failure; aborting run early.", flush=True)
                        subprocess.run(["taskkill", "/F", "/T", "/PID", str(proc.pid)], capture_output=True)
                        break
                except OSError:
                    pass
            time.sleep(1)
        try:
            proc.wait(timeout=30)
        except subprocess.TimeoutExpired:
            rc, res = ps("-File", str(WIN_PS1), "-Action", "close", "-ParentPid", str(proc.pid), "-RunId", run_id)
            if rc != 0:
                print(f"  Scoped window close returned {rc} ({res}); falling back to process-tree termination.", flush=True)
            try:
                proc.wait(timeout=15)
            except subprocess.TimeoutExpired:
                subprocess.run(["taskkill", "/F", "/T", "/PID", str(proc.pid)], capture_output=True)
                proc.wait(timeout=10)
        if pm_proc is not None:
            try:
                pm_proc.terminate()
                pm_proc.wait(timeout=10)
            except Exception:
                try:
                    pm_proc.kill()
                except Exception:
                    pass

    latest_log = run_dir / "logs" / "latest.log"
    copied_log = out_dir / f"latest-{label}-{backend.lower()}.log"
    if latest_log.exists():
        shutil.copy2(latest_log, copied_log)
    # Collect Spark profile dumps & CrashAssistant reports when present
    for spark_dir in (run_dir / "config" / "spark", run_dir / "spark"):
        if spark_dir.exists():
            for sp in sorted(spark_dir.rglob("*.sparkprofile")):
                shutil.copy2(sp, out_dir / f"spark-{label}-{backend.lower()}-{sp.name}")
    ca_dir = run_dir / "logs" / "crash_assistant"
    if ca_dir.exists():
        ca_dst = out_dir / f"crash-assistant-{label}-{backend.lower()}"
        shutil.copytree(ca_dir, ca_dst, dirs_exist_ok=True)
    cr_dir = run_dir / "crash-reports"
    if cr_dir.exists():
        for cr in sorted(cr_dir.glob("*.txt")):
            shutil.copy2(cr, out_dir / f"crash-{label}-{backend.lower()}-{cr.name}")

    if result_file.exists():
        data = json.loads(result_file.read_text(encoding="utf-8"))
        for sc_name, sc in data.get("scenes", {}).items():
            print(
                f"  {sc_name:20s}: {sc['avgFps']:7.1f} FPS | med {sc['medianMs']:6.3f} ms | "
                f"p99 {sc['p99Ms']:6.3f} ms | 1%L {sc['onePercentLowFps']:6.1f} | "
                f"0.1%L {sc['pointOnePercentLowFps']:5.1f} | alloc {sc['allocMbPerSec']:5.1f} MB/s",
                flush=True,
            )
        if pm_csv.exists():
            pres_out = out_dir / f"presentation-{label}-{backend.lower()}.json"
            pres = present_trace.correlate_run(result_file, pm_csv, copied_log if copied_log.exists() else None, pres_out)
            ov = pres["overallPresentation"]
            disp = ov["displayInterval"]
            print(
                f"  [PresentMon] displayed {disp['avgFps']:.1f} FPS | med {disp['medianMs']:.2f} ms | "
                f"p99 {disp['p99Ms']:.2f} ms | 1%L {disp['onePercentLowFps']:.1f} | "
                f"dropped {ov['droppedCount']}/{ov['presentCount']} ({ov['droppedPct']:.2f}%) | modes {ov['presentModes']}",
                flush=True,
            )
            data["presentation"] = pres
        return data
    else:
        print(f"  FAILED: no {result_file.name} produced (exit {proc.returncode}). Check {gradle_log}", flush=True)
        return None


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--targets", default="vanilla,sodium,perfpack")
    ap.add_argument("--backends", default="OPENGL,VULKAN")
    ap.add_argument("--out", default=str(REPO / "build" / "bench" / "results"))
    ap.add_argument("--duration", type=int, default=6)
    ap.add_argument("--no-jfr", action="store_true")
    ap.add_argument("--spark", action="store_true")
    ap.add_argument("--extra-jvm", default="")
    ap.add_argument("--render-distance", type=int, default=20)
    ap.add_argument("--suite", default="default", choices=["default", "gameplay", "all"])
    ap.add_argument("--minecraft", default="", help="comma-separated Minecraft versions (default: gradle.properties)")
    ap.add_argument("--fps-cap", type=int, default=0, help="explicit FPS cap for frame-pacing validation (0 = uncapped)")
    ap.add_argument("--vsync", action="store_true", help="enable VSync during benchmark run")
    ap.add_argument("--borderless", action="store_true", help="record borderless presentation mode metadata")
    ap.add_argument("--presentmon", default="", help="attach PresentMon ('auto' or path to PresentMon-x64.exe)")
    args = ap.parse_args()

    out_dir = Path(args.out).resolve()
    targets = [t.strip() for t in args.targets.split(",") if t.strip()]
    backends = [b.strip().upper() for b in args.backends.split(",") if b.strip()]

    versions = [v.strip() for v in args.minecraft.split(",") if v.strip()] or [default_minecraft()]

    summary = {}
    failed_runs: list[str] = []
    for backend in backends:
        for minecraft in versions:
            for target in targets:
                suffix = "" if minecraft == default_minecraft() else f"-mc{minecraft}"
                key = f"{target}{suffix}_{backend.lower()}"
                res = run_one(
                    target, backend, out_dir, args.duration, not args.no_jfr, args.extra_jvm,
                    args.render_distance, args.suite, args.spark, minecraft,
                    fps_cap=args.fps_cap, vsync=args.vsync, borderless=args.borderless, presentmon=args.presentmon,
                )
                if res:
                    summary[key] = res
                else:
                    failed_runs.append(key)

    (out_dir / "summary.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
    print(f"\nWrote {out_dir / 'summary.json'}", flush=True)
    if failed_runs:
        print(f"FAILED RUNS ({len(failed_runs)}): {', '.join(failed_runs)}", file=sys.stderr, flush=True)
        sys.exit(1)


if __name__ == "__main__":
    main()
