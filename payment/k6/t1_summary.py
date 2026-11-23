"""
T1 집계: t1-single.sh가 자른 요청별 로그에서 트랜잭션·SQL을 뽑는다.
사용: python3 -I k6/t1_summary.py <결과 폴더> [케이스 이름 ...]   (기본: H1-2 L1-2-win L1-2-lose)

트랜잭션마다 이름(서비스.메서드), 커밋/롤백, 실행 SQL 목록(짧게), Hibernate 세션 통계(커넥션 획득 수, 실행 문장 수)를 출력한다.
상품 행 FOR UPDATE부터 그 트랜잭션의 커밋/롤백까지 나간 SQL에는 [상품 락]을 붙인다. (커밋 직전 flush UPDATE 포함)
요청 스레드(첫 트랜잭션을 연 스레드)의 로그만 본다.
"""
import re
import sys

THREAD = re.compile(r"\[\s*([\w-]+)\] (\S+)\s+: (.*)$")
SQL_SHORT = [
    (re.compile(r"^select .*? from (\w+) .*?(for update|for share)", re.I), lambda m: f"SELECT {m[1]} {m[2].upper()}"),
    (re.compile(r"^select .*? from (\w+)", re.I), lambda m: f"SELECT {m[1]}"),
    (re.compile(r"^insert into (\w+)", re.I), lambda m: f"INSERT {m[1]}"),
    (re.compile(r"^update (\w+)", re.I), lambda m: f"UPDATE {m[1]}"),
    (re.compile(r"^delete from (\w+)", re.I), lambda m: f"DELETE {m[1]}"),
]


def short(sql):
    for pat, fmt in SQL_SHORT:
        m = pat.search(sql)
        if m:
            return fmt(m)
    return sql[:60]


def parse(path):
    txs, cur, thread, metrics_next = [], None, None, False
    for raw in open(path, encoding="utf-8", errors="replace"):
        line = raw.rstrip("\n")
        if metrics_next and cur is not None:
            m = re.search(r"acquiring (\d+) JDBC connections", line)
            if m:
                cur["conn"] = int(m[1])
            m = re.search(r"executing (\d+) JDBC statements", line)
            if m:
                cur["stmts"] = int(m[1])
                metrics_next = False
                cur = None
            continue
        m = THREAD.search(line)
        if not m:
            continue
        th, logger, msg = m[1], m[2], m[3]
        if thread is None and "Creating new transaction with name" in msg:
            thread = th
        if th != thread:
            continue
        if "Creating new transaction with name" in msg:
            name = re.search(r"name \[([^\]]+)\]", msg)[1].split(".")
            cur = {"name": ".".join(name[-2:]), "sql": [], "result": "?", "conn": None, "stmts": None, "locked": False}
            txs.append(cur)
        elif cur is None:
            continue
        elif logger.endswith("SQL"):
            s = short(msg)
            if s == "SELECT product FOR UPDATE":
                cur["locked"] = True
            cur["sql"].append(s + ("  [상품 락]" if cur["locked"] else ""))
        elif "Committing JPA transaction" in msg:
            cur["result"] = "커밋"
        elif "Rolling back JPA transaction" in msg:
            cur["result"] = "롤백"
        elif "Logging session metrics" in msg:
            metrics_next = True
    return txs


folder = sys.argv[1]
cases = sys.argv[2:] or ["H1-2", "L1-2-win", "L1-2-lose"]
for case in cases:
    txs = parse(f"{folder}/{case}.log")
    print(f"\n#### {case}: 트랜잭션 {len(txs)}개, SQL {sum(len(t['sql']) for t in txs)}개, "
          f"커넥션 획득 {sum(t['conn'] or 0 for t in txs)}회")
    for i, t in enumerate(txs, 1):
        print(f"{i}. {t['name']} — {t['result']} (커넥션 {t['conn']}, 문장 {t['stmts']})")
        for s in t["sql"]:
            print(f"     {s}")
