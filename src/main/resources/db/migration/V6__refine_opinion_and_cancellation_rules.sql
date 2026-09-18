ALTER TABLE research_cycle
    DROP CHECK ck_research_cycle_status,
    DROP CHECK ck_research_cycle_end_time;

ALTER TABLE research_cycle
    ADD CONSTRAINT ck_research_cycle_status
        CHECK (status IN ('ACTIVE', 'ENDED', 'CANCELLED')),
    ADD CONSTRAINT ck_research_cycle_end_time CHECK (
        (status = 'ACTIVE' AND ended_at IS NULL)
        OR (status IN ('ENDED', 'CANCELLED') AND ended_at IS NOT NULL AND ended_at >= started_at)
    );

ALTER TABLE stock_opinion
    DROP CHECK ck_stock_opinion_stance,
    DROP CHECK ck_stock_opinion_risk;

UPDATE stock_opinion
SET stance = 'BEARISH'
WHERE stance = 'CAUTIOUS';

ALTER TABLE stock_opinion
    CHANGE COLUMN risk_note observation_note VARCHAR(1000) NULL,
    ADD CONSTRAINT ck_stock_opinion_stance
        CHECK (stance IN ('BULLISH', 'NEUTRAL', 'BEARISH')),
    ADD CONSTRAINT ck_stock_opinion_observation
        CHECK (observation_note IS NULL OR CHAR_LENGTH(TRIM(observation_note)) BETWEEN 1 AND 1000);
