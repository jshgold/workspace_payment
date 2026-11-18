"""k6/thread-starvation.js 결과를 구간별로 나눠 요약한다.

사용법: python3 -I k6/analyze_starvation.py <k6 --out json 파일> <k6 콘솔 로그 파일>

- 결제 구간 = 첫 결제 요청 시작 ~ 마지막 결제 응답 종료
- 상품 조회(browse) 요청을 "시작 시각"으로 결제 전 / 결제 중 / 결제 후로 나눠 응답 시간을 비교한다.
"""
import json
import re
import sys
from collections import Counter


def percentile(values, p):
    if not values:
        return float("nan")
    s = sorted(values)
    k = (len(s) - 1) * p / 100
    lo, hi = int(k), min(int(k) + 1, len(s) - 1)
    return s[lo] + (s[hi] - s[lo]) * (k - lo)


def summary(values):
    if not values:
        return "건수 0"
    return (f"건수 {len(values)}, 평균 {sum(values) / len(values):.1f}ms, "
            f"중앙값 {percentile(values, 50):.1f}ms, p95 {percentile(values, 95):.1f}ms, 최대 {max(values):.1f}ms")


def main(json_path, log_path):
    browse = []  # (start_ms, duration_ms, status)
    with open(json_path, encoding="utf-8") as f:
        for line in f:
            point = json.loads(line)
            if point.get("type") != "Point" or point.get("metric") != "http_req_duration":
                continue
            tags = point["data"].get("tags", {})
            if tags.get("kind") == "browse":
                browse.append((int(tags["start_ms"]), point["data"]["value"], tags.get("status")))

    pays = []  # (start, end, status, outcome)
    pg_stats = None
    pattern = re.compile(r"PAY_RESULT start=(\d+) end=(\d+) status=(\d+) outcome=(\w+)")
    with open(log_path, encoding="utf-8") as f:
        for line in f:
            m = pattern.search(line)
            if m:
                pays.append((int(m[1]), int(m[2]), m[3], m[4]))
            elif "PG_STATS" in line:
                pg_stats = re.search(r"PG_STATS ([^\"]*)", line)[1]

    if not pays:
        sys.exit("결제 결과 로그(PAY_RESULT)가 없다.")
    window_start = min(p[0] for p in pays)
    window_end = max(p[1] for p in pays)
    pay_durations = [p[1] - p[0] for p in pays]

    print(f"결제 구간: {window_end - window_start}ms (결제 {len(pays)}건)")
    print(f"결제 결과: {dict(Counter(p[3] for p in pays))}")
    print(f"PG: {pg_stats}")
    for outcome in sorted(set(p[3] for p in pays)):
        print(f"  결제 응답 시간 [{outcome}] {summary([p[1] - p[0] for p in pays if p[3] == outcome])}")
    print(f"  결제 응답 시간 [전체] {summary(pay_durations)}")

    before = [d for s, d, _ in browse if s < window_start]
    during = [d for s, d, _ in browse if window_start <= s <= window_end]
    after = [d for s, d, _ in browse if s > window_end]
    print("상품 조회 응답 시간")
    print(f"  결제 전 {summary(before)}")
    print(f"  결제 중 {summary(during)}")
    print(f"  결제 후 {summary(after)}")
    print(f"  상태 코드: {dict(Counter(st for _, _, st in browse))}")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    main(sys.argv[1], sys.argv[2])
