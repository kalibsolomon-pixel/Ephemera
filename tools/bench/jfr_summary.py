"""Quick JFR render-thread hotspot summarizer using JDK's `jfr` CLI."""
import json
import subprocess
import sys
from collections import Counter
from pathlib import Path


def summarize_jfr(jfr_path: Path, top_n: int = 25) -> dict:
    proc = subprocess.run(
        ["jfr", "print", "--events", "jdk.ExecutionSample", "--json", str(jfr_path)],
        capture_output=True,
        text=True,
        check=True,
    )
    data = json.loads(proc.stdout)
    events = data.get("recording", {}).get("events", [])
    render_samples = 0
    leaf = Counter()
    inclusive = Counter()
    for ev in events:
        vals = ev.get("values", {})
        thread = vals.get("sampledThread", {}).get("javaName", "")
        if thread != "Render thread":
            continue
        render_samples += 1
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
        "renderSamples": render_samples,
        "topInclusive": [
            {"method": k, "samples": v, "pct": round(100.0 * v / max(1, render_samples), 2)}
            for k, v in inclusive.most_common(top_n)
        ],
        "topLeaf": [
            {"method": k, "samples": v, "pct": round(100.0 * v / max(1, render_samples), 2)}
            for k, v in leaf.most_common(top_n)
        ],
    }


if __name__ == "__main__":
    for arg in sys.argv[1:]:
        p = Path(arg)
        res = summarize_jfr(p)
        print(f"\n=== JFR: {p.name} ({res['renderSamples']} Render-thread samples) ===")
        print("Top Inclusive:")
        for row in res["topInclusive"][:20]:
            print(f"  {row['pct']:5.2f}% ({row['samples']:4d})  {row['method']}")
        print("Top Self/Leaf:")
        for row in res["topLeaf"][:15]:
            print(f"  {row['pct']:5.2f}% ({row['samples']:4d})  {row['method']}")
