"""
재측정 결과 집계. 사용: python3 -I k6/summarize.py <결과 폴더(run-all.sh 출력)> <서버 로그> k6/analyze_timing.py
시나리오마다 run 1~3(워밍업 run 0 제외)을 모은다.
- k6 지표(응답 시간 평균·p95): 실행별 값의 평균
- 서버 구간: 3회 실행의 요청을 한데 모아 결과(OK/FAIL)별 평균, p95
- PG 승인/취소, 최종 재고: 실행별 값 (3회가 같아야 한다)
"""
import importlib.util
import re
import sys
from collections import defaultdict

ver_dir, server_log, analyze_path = sys.argv[1], sys.argv[2], sys.argv[3]
spec = importlib.util.spec_from_file_location("analyze_timing", analyze_path)
at = importlib.util.module_from_spec(spec)
spec.loader.exec_module(at)

ranges = defaultdict(dict)
order = []
for line in open(f"{ver_dir}/ranges.txt", encoding="utf-8"):
    sid, run, first, last = line.split()
    if sid not in order:
        order.append(sid)
    ranges[sid][int(run)] = (int(first), int(last))


def k6_metrics(path):
    text = open(path, encoding="utf-8", errors="replace").read()
    out = {}
    for name in ("approve_200", "approve_409", "approve_other"):
        m = re.search(rf"{name}\.*:\s+(\d+)", text)
        out[name] = int(m.group(1)) if m else 0
    for kind in ("success", "failure"):
        m = re.search(rf"approve_latency_{kind}\.*:\s+avg=([\d.]+)(ms|s|µs).*?p\(95\)=([\d.]+)(ms|s|µs)", text)
        if m:
            conv = lambda v, u: float(v) * {"ms": 1, "s": 1000, "µs": 0.001}[u]
            out[kind] = (conv(m.group(1), m.group(2)), conv(m.group(3), m.group(4)))
    m = re.search(r"PG 승인 호출: (\d+)회", text)
    out["pg_approve"] = int(m.group(1)) if m else None
    m = re.search(r"PG 취소 호출: (\d+)회", text)
    out["pg_cancel"] = int(m.group(1)) if m else None
    m = re.search(r"최종 재고 합: (-?\d+) \(처음 (\d+)\), 음수 재고 상품: (\d+)개", text)
    out["stock"] = (int(m.group(1)), int(m.group(2)), int(m.group(3))) if m else None
    return out


def mean(xs):
    return sum(xs) / len(xs) if xs else None


def fmt(v, digits=1):
    return "-" if v is None else f"{v:.{digits}f}"


for sid in order:
    runs = [k6_metrics(f"{ver_dir}/{sid}-{r}.log") for r in (1, 2, 3)]
    print(f"\n#### {sid}")
    print("k6  : 200/409/기타 = " + ", ".join(f"{m['approve_200']}/{m['approve_409']}/{m['approve_other']}" for m in runs)
          + " | PG 승인/취소 = " + ", ".join(f"{m['pg_approve']}/{m['pg_cancel']}" for m in runs)
          + " | 재고 합(처음, 음수상품) = " + ", ".join(f"{m['stock'][0]}({m['stock'][1]},{m['stock'][2]})" for m in runs))
    for kind, label in (("success", "성공"), ("failure", "실패")):
        vals = [m[kind] for m in runs if kind in m]
        if vals:
            print(f"k6  : {label} 응답 평균 {fmt(mean([v[0] for v in vals]))}ms (실행별 {', '.join(fmt(v[0]) for v in vals)})"
                  f", p95 평균 {fmt(mean([v[1] for v in vals]))}ms")
    first, last = ranges[sid][1][0], ranges[sid][3][1]
    data, counts = at.parse(server_log, first, last)
    for result in sorted(counts):
        segs = data[result]
        names = [n for n in at.SEGMENT_ORDER if n in segs] + sorted(n for n in segs if n not in at.SEGMENT_ORDER)
        parts = []
        for n in names:
            s = segs[n]
            t = mean(s["time"])
            if s.get("conn"):
                parts.append(f"{n} {t:.1f}(conn {mean(s['conn']):.1f}/commit {mean(s['commit']):.1f}/rest {mean(s['rest']):.1f})")
            else:
                parts.append(f"{n} {t:.1f}")
        db = [n for n in names if segs[n].get("conn")]
        sums = {k: sum(sum(segs[n][k]) for n in db) / counts[result] for k in ("time", "conn", "commit", "rest")}
        db_total = [sum(x) for x in zip(*[segs[n]["time"] for n in db])] if db and all(len(segs[n]["time"]) == counts[result] for n in db) else []
        p95 = at.percentile(db_total, 95) if db_total else None
        print(f"서버: {result} {counts[result]}건 | DB 합 {sums['time']:.1f} (conn {sums['conn']:.1f}, commit {sums['commit']:.1f}, "
              f"rest {sums['rest']:.1f}), DB 합 p95 {fmt(p95)} | total p95 {fmt(at.percentile(segs['total']['time'], 95))}")
        print("      " + " | ".join(parts))
