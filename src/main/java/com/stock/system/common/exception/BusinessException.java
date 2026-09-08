package com.stock.system.common.exception;

import com.stock.system.common.api.ErrorCode;

/**
 * 已预料且可以安全返回给调用方的业务错误。
 */
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.message());
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
