package com.stock.stocksystem.common;

import lombok.Data;

@Data
public class Result<T> {
    private Integer code;   // 1 成功，0 失败
    private String msg;     // 提示信息
    private T data;         // 数据，可以是任何类型

    // 成功，不带数据
    public static <T> Result<T> success() {
        Result<T> r = new Result<>();
        r.code = 1;
        r.msg = "成功";
        return r;
    }

    // 成功，带数据
    public static <T> Result<T> success(T data) {
        Result<T> r = new Result<>();
        r.code = 1;
        r.msg = "成功";
        r.data = data;
        return r;
    }

    // 失败
    public static <T> Result<T> error(String msg) {
        Result<T> r = new Result<>();
        r.code = 0;
        r.msg = msg;
        return r;
    }
}