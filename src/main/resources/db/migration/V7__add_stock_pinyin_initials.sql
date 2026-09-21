ALTER TABLE stock
    ADD COLUMN pinyin_initials VARCHAR(50) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT ''
        AFTER search_aliases,
    ADD CONSTRAINT ck_stock_pinyin_initials
        CHECK (pinyin_initials = '' OR pinyin_initials REGEXP '^[a-z]+$');
