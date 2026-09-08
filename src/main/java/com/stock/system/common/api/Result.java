package com.stock.system.common.api;

/**
 * 所有 HTTP API 共用的响应外壳。
 *
 * code 供程序稳定判断结果，message 供用户阅读，data 保存业务结果。
 */
public record Result<T>(String code, String message, T data) {

    private static final String SUCCESS_CODE = "SUCCESS";
    private static final String SUCCESS_MESSAGE = "成功";

    public static <T> Result<T> success(T data) {
        return new Result<>(SUCCESS_CODE, SUCCESS_MESSAGE, data);
    }

    public static Result<Void> error(ErrorCode errorCode) {
        return new Result<>(errorCode.name(), errorCode.message(), null);
    }
}
