#!/bin/zsh
# T1: 트랜잭션·SQL 수 실측용 단건 요청 (docs/troubleshooting/test-list.md 1-5 T1)
# 사용: zsh k6/t1-single.sh <서버 로그 파일> <결과 폴더> [BASE_URL]
# 서버는 SQL 로그·Hibernate 세션 통계·트랜잭션 로그를 켜고, 가짜 PG 지연 0으로 띄워 둔다.
# 승인 요청 1건마다 그 요청 동안 늘어난 서버 로그만 잘라 <결과 폴더>/<케이스>.log로 남긴다. (상품·주문 생성 로그는 제외)
#   H1-1, H1-2 : 재고 1 상품에 주문 1건 승인 (이긴 요청)
#   L1-1, L1-2 : 재고 1 상품에 주문 2건을 순차 승인. 첫 번째(이긴 요청)는 L1-n-win, 두 번째(진 요청)는 L1-n-lose
# 첫 실행에는 초기화 쿼리가 섞일 수 있으므로 -2를 기준으로 본다.
LOG=$1
OUT=$2
BASE=${3:-http://localhost:8080}
if [[ -z $LOG || -z $OUT ]]; then
  echo "사용: zsh k6/t1-single.sh <서버 로그> <결과 폴더> [BASE_URL]" >&2
  exit 1
fi
mkdir -p $OUT
J='Content-Type: application/json'

pause() { perl -e 'select(undef,undef,undef,1)'; }

new_product() {
  curl -s -X POST $BASE/api/products -H $J -d "{\"name\":\"t1-$1-$(date +%s)-$RANDOM\",\"price\":10000,\"stock\":1}" | jq -r .id
}
new_order() {
  curl -s -X POST $BASE/api/orders -H $J -d "{\"memberId\":$2,\"productId\":$1,\"quantity\":1}" | jq -c '{id,totalPrice}'
}
approve() {
  local name=$1 order=$2
  local id=$(echo $order | jq -r .id) amount=$(echo $order | jq -r .totalPrice)
  pause
  local before=$(wc -l < $LOG)
  local code=$(curl -s -o $OUT/$name.body -w '%{http_code}' -X POST $BASE/api/orders/$id/payments/approve \
    -H $J -H "Idempotency-Key: t1-$id-$(date +%s)-$RANDOM" -d "{\"amount\":$amount,\"paymentKey\":\"t1-payment-key-$id\"}")
  pause
  local after=$(wc -l < $LOG)
  sed -n "$((before + 1)),${after}p" $LOG > $OUT/$name.log
  echo "$name orderId=$id status=$code 로그 $((after - before))줄"
}

curl -s -o /dev/null -w 'fake-pg reset %{http_code}\n' -X POST $BASE/api/fake-pg/reset

for n in 1 2; do
  p=$(new_product H1)
  approve H1-$n "$(new_order $p 100)"
done

for n in 1 2; do
  p=$(new_product L1)
  o1=$(new_order $p 100)
  o2=$(new_order $p 101)
  approve L1-$n-win "$o1"
  approve L1-$n-lose "$o2"
done
echo "DONE"
