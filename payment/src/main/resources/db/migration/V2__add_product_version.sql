-- 낙관적 락(@Version)용 버전 컬럼. 기존 행은 0부터 시작한다.
ALTER TABLE product
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
