package com.stock.system.stock.market;

import java.util.List;

interface LongbridgeIntradayGateway {

    List<LongbridgeIntradayPoint> getIntraday(String symbol);
}
