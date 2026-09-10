package com.stock.system.stock.market;

import java.util.List;
import java.util.Map;

interface LongbridgeQuoteGateway {

    Map<String, LongbridgeQuote> getQuotes(List<String> symbols);
}
