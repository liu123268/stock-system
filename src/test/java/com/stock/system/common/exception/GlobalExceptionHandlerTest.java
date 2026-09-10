package com.stock.system.common.exception;

import com.stock.system.common.api.ErrorCode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    @Test
    void returnsSafeBusinessExceptionMessageWhenItContainsUsefulBoundaryDetails() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        BusinessException exception = new BusinessException(
                ErrorCode.MARKET_INTRADAY_HISTORY_RANGE_EXCEEDED,
                "当前账户的历史分钟行情最早可查看至 2023-09-01，请选择该日期或之后的交易日"
        );

        var response = handler.handleBusinessException(exception);

        assertThat(response.getStatusCode().value()).isEqualTo(422);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code())
                .isEqualTo("MARKET_INTRADAY_HISTORY_RANGE_EXCEEDED");
        assertThat(response.getBody().message()).contains("2023-09-01");
    }
}
