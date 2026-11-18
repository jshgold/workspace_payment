  토스페이먼츠 테스트 환경에서 결제창으로 paymentKey를 받기 위한 "수동 테스트용 정적 HTML 페이지"를 만들어줘.
  이 페이지는 결제 UI가 아니라, 우리 서버의 결제 승인 API를 수동 확인할 때 paymentKey를 얻는 최소 도구야.

  ## 먼저 할 것 (구현 전)
  - 구현 전에 토스페이먼츠 공식 문서로 아래를 확인하고, 기억에 의존하지 마.
    - 결제창 SDK(v2)의 스크립트 주소, 초기화 방법, requestPayment 파라미터 형식
    - 클라이언트 키 종류(결제창용 키와 API 개별 연동 키의 차이)와 이 연동에 맞는 키
    - successUrl / failUrl로 돌아올 때 쿼리 파라미터(paymentKey, orderId, amount 등)
    - 문서 원문은 https://docs.tosspayments.com/llms.txt 에서 목록을 찾고, 각 문서 URL 뒤에 .md 를 붙이면 마크다운으로 읽을 수 있어.
  - 확인한 문서 URL을 결과 보고에 적어줘.

  ## 만들 것
  - 파일 1개: ./toss-test-checkout.html (외부 빌드 도구 없이, 브라우저에서 파일 또는 간단한 정적 서버로 열 수 있어야 해)
  - 입력 필드:
    - 클라이언트 키 (테스트 키, 직접 입력)
    - 주문번호 orderId (우리 DB의 orders.order_no 값을 붙여넣음. 36자 UUID 문자열)
    - 결제 금액 (정수, 원)
    - 주문명
  - 동작:
    1) 버튼을 누르면 입력한 값으로 결제창을 연다 (카드 결제 기준).
    2) 결제 성공 후 돌아오면 같은 페이지에서 쿼리 파라미터의 paymentKey, orderId, amount를 화면에 크게 표시한다.
    3) 아래 curl 명령을 값이 채워진 상태로 만들어 복사 버튼과 함께 보여준다. (Idempotency-Key는 crypto.randomUUID()로 생성)
       curl -X POST "http://localhost:8080/api/orders/{주문ID}/payments/approve" \
         -H "Content-Type: application/json" \
         -H "Idempotency-Key: {uuid}" \
         -d '{"amount": {amount}, "paymentKey": "{paymentKey}"}'
       {주문ID}는 우리 서버 내부 PK(숫자)라서 페이지에서 별도 입력 필드로 받는다.
    4) 실패(failUrl)로 돌아오면 code, message를 표시한다.
  - 서버 API를 페이지에서 직접 호출하지는 마. curl 문자열만 만들어줘. (CORS와 키 관리 문제를 피하기 위해서야)

  ## 제약
  - 어떤 키도 파일에 하드코딩하지 마. 클라이언트 키는 입력창으로만 받고, localStorage 등에 저장하지 마.
  - 시크릿 키는 이 페이지에 절대 넣지 않는다. (브라우저에 노출되면 안 되는 키야)
  - 프레임워크, 번들러, npm 사용 금지. 순수 HTML/CSS/JS 한 파일.
  - 스타일은 최소한으로. 화면은 한국어.
  - 기존 프로젝트 파일(src/, build.gradle.kts 등)은 수정하지 마. docs/ 아래 새 파일만 만든다.
  - .env, 시크릿 파일은 읽지 마. git commit이나 push는 하지 마.

  ## 완료 보고
  - 만든 파일, 확인한 공식 문서 URL, 문서와 다르게 처리한 부분이 있다면 그 이유
  - 페이지를 여는 방법 (예: python3 -m http.server 로 docs/ 를 서빙하고 접속할 주소)
  - 문서로 확인하지 못했거나 불확실한 부분
