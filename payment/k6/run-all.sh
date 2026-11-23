#!/bin/zsh
# 재측정 실행 스크립트 (docs/troubleshooting/test-list.md 1-5 시나리오, 순서 고정)
# 사용: zsh k6/run-all.sh <결과 폴더> [k6 스크립트 경로 (기본: 이 파일 옆의 stock-contention.js)]
# 시나리오마다 워밍업 1회(run 0) + 본 측정 3회(run 1~3). 실행별 k6 로그와 orderId 범위(ranges.txt)를 결과 폴더에 남긴다.
# 서버(가짜 PG, payment.timing=debug)를 먼저 띄워 둔다. 집계는 k6/summarize.py로 한다.
OUT=$1
SCRIPT=${2:-${0:A:h}/stock-contention.js}
if [[ -z $OUT ]]; then
  echo "사용: zsh k6/run-all.sh <결과 폴더> [k6 스크립트]" >&2
  exit 1
fi
mkdir -p $OUT
: > $OUT/ranges.txt

scenarios=(
  "H1|-e ORDERS=1 -e STOCK=1"
  "L1|-e ORDERS=2 -e STOCK=1 -e SEQUENTIAL=true"
  "C2|-e ORDERS=2 -e STOCK=1"
  "H10-분산|-e ORDERS=10 -e STOCK=1 -e PRODUCTS=10"
  "H10-공유|-e ORDERS=10 -e STOCK=10"
  "C10-분산|-e ORDERS=10 -e STOCK=1 -e PRODUCTS=5"
  "C10-집중|-e ORDERS=10 -e STOCK=1"
  "H200-분산|-e ORDERS=200 -e STOCK=1 -e PRODUCTS=200"
  "H200-공유|-e ORDERS=200 -e STOCK=200"
  "C200-분산|-e ORDERS=200 -e STOCK=1 -e PRODUCTS=100"
  "C200-집중|-e ORDERS=200 -e STOCK=1"
)

for entry in $scenarios; do
  id=${entry%%|*}
  args=(${=entry#*|})
  for r in 0 1 2 3; do
    log=$OUT/$id-$r.log
    k6 run $args $SCRIPT > $log 2>&1
    range=$(grep -oE 'orderId [0-9]+~[0-9]+' $log | head -1 | sed -E 's/orderId ([0-9]+)~([0-9]+)/\1 \2/')
    echo "$id $r $range" >> $OUT/ranges.txt
    echo "$id run$r orderId $range"
    perl -e 'select(undef,undef,undef,2)'
  done
done
echo "ALL DONE"
