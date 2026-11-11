CREATE TABLE product (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    name       VARCHAR(100) NOT NULL,
    price      BIGINT       NOT NULL,
    stock      INT          NOT NULL,
    status     VARCHAR(20)  NOT NULL,
    deleted_at DATETIME(6)  NULL,
    created_at DATETIME(6)  NOT NULL,
    updated_at DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT ck_product_price CHECK (price > 0),
    CONSTRAINT ck_product_stock CHECK (stock >= 0),
    CONSTRAINT ck_product_status CHECK (status IN ('ON_SALE', 'SOLD_OUT', 'STOPPED'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
