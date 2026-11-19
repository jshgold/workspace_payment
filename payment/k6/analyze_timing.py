"""
결제 승인 구간별 소요 시간 집계 (측정 전용).

서버를 `--logging.level.payment.timing=debug`로 띄우면 PaymentSegmentTimer가 요청당 한 줄을 남긴다.
  orderId=1 result=OK begin=95.9ms(conn=88.8ms,commit=2.5ms) startPaying=... pgApprove=301.6ms ... etc=0.0ms total=677.4ms
이 스크립트는 로그 파일에서 그 줄을 모아 결과(OK / FAIL / REPLAY)별, 구간별로 평균·p50·p95·최대를 낸다.
DB 구간은 conn(커넥션 대기), commit, rest(= 구간 - conn - commit: SQL 실행 + 행 락 대기 + 코드)로 나눠 보여 준다.

사용법:
  python3 -I k6/analyze_timing.py <서버 로그 파일> [--since-order-id N] [--until-order-id M]
  --since-order-id / --until-order-id: N 이상 M 이하인 orderId만 집계한다. 로그 하나에 여러 번 측정했을 때 구분용.
"""

import argparse
import re
import sys
from collections import defaultdict

LINE = re.compile(r"orderId=(\d+) result=(\w+)(.*)$")
SEGMENT = re.compile(r"(\w+)=([\d.]+)ms(?:\(conn=([\d.]+)ms,commit=([\d.]+)ms\))?")

SEGMENT_ORDER = ["begin", "startPaying", "pgApprove", "complete", "pgCancel",
                 "revertToPending", "succeed", "fail", "etc", "total"]


def percentile(values, p):
    ordered = sorted(values)
    if not ordered:
        return 0.0
    index = min(len(ordered) - 1, max(0, round(p / 100 * len(ordered) + 0.5) - 1))
    return ordered[index]


def summarize(values):
    return (sum(values) / len(values), percentile(values, 50), percentile(values, 95), max(values))


def parse(path, since_order_id, until_order_id):
    # result -> segment -> metric(total/conn/commit/rest) -> [ms]
    data = defaultdict(lambda: defaultdict(lambda: defaultdict(list)))
    counts = defaultdict(int)
    with open(path, encoding="utf-8", errors="replace") as f:
        for raw in f:
            if "payment.timing" not in raw and "PaymentSegmentTimer" not in raw:
                continue
            m = LINE.search(raw)
            if not m:
                continue
            order_id, result, rest = int(m.group(1)), m.group(2), m.group(3)
            if since_order_id is not None and order_id < since_order_id:
                continue
            if until_order_id is not None and order_id > until_order_id:
                continue
            counts[result] += 1
            for seg in SEGMENT.finditer(rest):
                name, ms = seg.group(1), float(seg.group(2))
                metrics = data[result][name]
                metrics["time"].append(ms)
                if seg.group(3) is not None:
                    conn, commit = float(seg.group(3)), float(seg.group(4))
                    metrics["conn"].append(conn)
                    metrics["commit"].append(commit)
                    metrics["rest"].append(ms - conn - commit)
    return data, counts


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("log")
    parser.add_argument("--since-order-id", type=int, default=None)
    parser.add_argument("--until-order-id", type=int, default=None)
    args = parser.parse_args()

    data, counts = parse(args.log, args.since_order_id, args.until_order_id)
    if not counts:
        print("payment.timing 줄이 없다. 서버를 --logging.level.payment.timing=debug로 띄웠는지 확인한다.")
        sys.exit(1)

    for result in sorted(counts):
        print(f"\n=== result={result} ({counts[result]}건) — 단위 ms, 평균 / p50 / p95 / 최대 ===")
        print(f"{'구간':<16}{'건수':>6}  {'시간':<32}{'conn':<32}{'commit':<32}{'rest':<32}")
        segments = data[result]
        names = [n for n in SEGMENT_ORDER if n in segments] + sorted(n for n in segments if n not in SEGMENT_ORDER)
        for name in names:
            metrics = segments[name]
            row = f"{name:<16}{len(metrics['time']):>6}  "
            for key in ("time", "conn", "commit", "rest"):
                values = metrics.get(key)
                row += (" / ".join(f"{v:.1f}" for v in summarize(values)) if values else "-").ljust(30) + "  "
            print(row)

        # DB 구간 합계: 요청 1건이 DB에서 쓴 시간 (PG·etc·total 제외)
        db_segments = [n for n in names if segments[n].get("conn")]
        if db_segments:
            sums = {key: sum(sum(segments[n][key]) for n in db_segments) / counts[result]
                    for key in ("time", "conn", "commit", "rest")}
            print(f"{'DB 구간 합(건당)':<16}{'':>6}  "
                  f"{sums['time']:.1f}ms (conn {sums['conn']:.1f}, commit {sums['commit']:.1f}, rest {sums['rest']:.1f})")


if __name__ == "__main__":
    main()
