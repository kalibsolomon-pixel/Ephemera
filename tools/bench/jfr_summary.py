"""Quick JFR render-thread hotspot summarizer using JDK's `jfr` CLI."""
import json
import subprocess
import sys
from collections import Counter
from pathlib import Path


def summarize_jfr(jfr_path: Path, top_n: int = 25, thread_filter: str = "Render thread") -> dict:
    proc = subprocess.run(
        ["jfr", "print", "--events", "jdk.ExecutionSample", "--json", str(jfr_path)],
        capture_output=True,
        text=True,
        check=True,
    )
    data = json.loads(proc.stdout)
    events = data.get("recording", {}).get("events", [])
    matched_samples = 0
    leaf = Counter()
    inclusive = Counter()
    thread_counts = Counter()
    for ev in events:
        vals = ev.get("values", {})
        thread = vals.get("sampledThread", {}).get("javaName", "")
        bucket = "Worker-Main" if thread.startswith("Worker-Main") else thread
        thread_counts[bucket] += 1
        if thread_filter == "ALL":
            pass
        elif thread_filter.endswith("*"):
            if not thread.startswith(thread_filter[:-1]):
                continue
        elif thread != thread_filter:
            continue
        matched_samples += 1
        frames = vals.get("stackTrace", {}).get("frames", [])
        seen = set()
        for idx, fr in enumerate(frames):
            m = fr.get("method", {})
            cls = m.get("type", {}).get("name", "?")
            name = m.get("name", "?")
            sig = f"{cls}.{name}"
            if idx == 0:
                leaf[sig] += 1
            if sig not in seen:
                inclusive[sig] += 1
                seen.add(sig)

    return {
        "file": str(jfr_path),
        "threadFilter": thread_filter,
        "renderSamples": matched_samples,
        "threadCounts": thread_counts.most_common(8),
        "topInclusive": [
            {"method": k, "samples": v, "pct": round(100.0 * v / max(1, matched_samples), 2)}
            for k, v in inclusive.most_common(top_n)
        ],
        "topLeaf": [
            {"method": k, "samples": v, "pct": round(100.0 * v / max(1, matched_samples), 2)}
            for k, v in leaf.most_common(top_n)
        ],
    }


if __name__ == "__main__":
    thread_filter = "Render thread"
    args = sys.argv[1:]
    if args and args[0].startswith("--thread="):
        thread_filter = args[0].split("=", 1)[1]
        args = args[1:]
    for arg in args:
        p = Path(arg)
        res = summarize_jfr(p, thread_filter=thread_filter)
        threads_str = ", ".join(f"{k}:{v}" for k, v in res["threadCounts"][:6])
        print(f"\n=== JFR: {p.name} [{thread_filter}] ({res['renderSamples']} samples; threads: {threads_str}) ===")
        print("Top Inclusive:")
        for row in res["topInclusive"][:18]:
            print(f"  {row['pct']:5.2f}% ({row['samples']:4d})  {row['method']}")
        print("Top Self/Leaf:")
        for row in res["topLeaf"][:14]:
            print(f"  {row['pct']:5.2f}% ({row['samples']:4d})  {row['method']}")
