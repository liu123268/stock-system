package com.stock.system.stock.market;

/** Transport boundary: the cache decorates this interface instead of a particular CLI/SDK client. */
interface LongbridgeUpstreamGateway extends LongbridgeQuoteGateway, LongbridgeCandlestickGateway,
        LongbridgeIntradayGateway, LongbridgeTradingDayGateway {
}
