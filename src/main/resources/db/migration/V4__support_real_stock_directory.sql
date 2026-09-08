ALTER TABLE stock
    DROP CHECK ck_stock_exchange,
    DROP CHECK ck_stock_listing_status,
    ADD CONSTRAINT ck_stock_exchange CHECK (exchange IN ('SSE', 'SZSE', 'BSE', 'HKEX')),
    ADD CONSTRAINT ck_stock_listing_status CHECK (listing_status IN ('LISTED', 'SUSPENDED', 'DELISTED', 'UNKNOWN')),
    ADD COLUMN search_aliases VARCHAR(200) NOT NULL DEFAULT '',
    ADD COLUMN last_synced_at DATETIME(3) NULL;
