"""26.2 vs 26.3 verdict per backend/scene. Usage: compare_versions.py <dir with {gl,vk}-rep*/ runs>

Margin of error per scene/metric = the larger relative spread between the two repeated runs of
Ephemera 26.2 and of Ephemera 26.3 (|a-b| / mean), floored at 3%. 26.3 passes a metric when
mean(26.3) >= mean(26.2) * (1 - margin).
"""
import json
import statistics
import sys
from pathlib import Path

root = Path(sys.argv[1])
FLOOR = 0.03


def load(pattern):
    out = []
    for f in sorted(root.glob(pattern)):
        out.append(json.loads(f.read_text(encoding='utf-8'))['scenes'])
    return out


def spread(vals):
    if len(vals) < 2:
        return 0.0
    m = statistics.mean(vals)
    return (max(vals) - min(vals)) / m if m else 0.0


fail_count = 0
for backend, tag in [('opengl', 'gl'), ('vulkan', 'vk')]:
    e262 = load(f'{tag}-rep*/bench-ephemera-{backend}.json')
    e263 = load(f'{tag}-rep*/bench-ephemera-mc26.3-{backend}.json')
    v262 = load(f'{tag}-rep*/bench-vanilla-{backend}.json')
    v263 = load(f'{tag}-rep*/bench-vanilla-mc26.3-{backend}.json')
    if not e262 or not e263:
        print(f'## {backend}: missing runs (26.2={len(e262)}, 26.3={len(e263)})')
        continue
    print(f'## {backend.upper()}  (runs: eph26.2={len(e262)} eph26.3={len(e263)} van26.2={len(v262)} van26.3={len(v263)})')
    print(f'{"scene":20s} {"metric":6s} {"eph26.2":>8s} {"eph26.3":>8s} {"delta":>7s} {"margin":>6s} {"van26.2":>8s} {"van26.3":>8s}  verdict')
    for scene in e262[0]:
        for metric, key in [('avg', 'avgFps'), ('1%L', 'onePercentLowFps')]:
            a = [r[scene][key] for r in e262 if scene in r]
            b = [r[scene][key] for r in e263 if scene in r]
            if not a or not b:
                continue
            ma, mb = statistics.mean(a), statistics.mean(b)
            margin = max(FLOOR, spread(a), spread(b))
            delta = mb / ma - 1
            ok = mb >= ma * (1 - margin)
            fail_count += 0 if ok else 1
            va = statistics.mean([r[scene][key] for r in v262 if scene in r]) if v262 else float('nan')
            vb = statistics.mean([r[scene][key] for r in v263 if scene in r]) if v263 else float('nan')
            print(f'{scene:20s} {metric:6s} {ma:8.0f} {mb:8.0f} {delta:+7.1%} {margin:6.1%} {va:8.0f} {vb:8.0f}  {"PASS" if ok else "FAIL"}')
    print()
print(f'FAILURES: {fail_count}')
