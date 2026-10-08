alter table stock
    add column trade_status varchar(40) character set ascii collate ascii_bin null,
    add column status_checked_at datetime(3) null,
    add column status_attempted_at datetime(3) null;

-- Existing listing_status is historical, not evidence of a fresh provider check.
-- Directory imports and research history keep their identifiers and original values.
