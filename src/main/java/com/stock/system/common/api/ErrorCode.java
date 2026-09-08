package com.stock.system.common.api;

import org.springframework.http.HttpStatus;

/**
 * 程序可以稳定识别的错误，以及每类错误默认对应的 HTTP 状态。
 */
public enum ErrorCode {
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "请求参数不合法"),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "未登录或登录已过期"),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "登录账号或密码错误"),
    ACCOUNT_DISABLED(HttpStatus.UNAUTHORIZED, "账号当前不可用"),
    USERNAME_ALREADY_EXISTS(HttpStatus.CONFLICT, "登录账号已存在"),
    WORKSPACE_NAME_ALREADY_EXISTS(HttpStatus.CONFLICT, "空间名称已被使用"),
    WORKSPACE_NOT_FOUND(HttpStatus.NOT_FOUND, "空间不存在"),
    WORKSPACE_ACCESS_DENIED(HttpStatus.FORBIDDEN, "当前用户不能执行该空间操作"),
    WORKSPACE_MEMBERSHIP_INACTIVE(HttpStatus.FORBIDDEN, "当前空间成员关系已失效"),
    WORKSPACE_ARCHIVED(HttpStatus.CONFLICT, "已归档空间不能执行该操作"),
    MEMBER_NOT_FOUND(HttpStatus.NOT_FOUND, "空间成员不存在"),
    MEMBER_NOT_ACTIVE(HttpStatus.CONFLICT, "目标成员当前不是有效成员"),
    OWNER_CANNOT_LEAVE(HttpStatus.CONFLICT, "空间所有者必须先转让所有权才能退出"),
    TRANSFER_TARGET_NOT_ACTIVE_MEMBER(HttpStatus.CONFLICT, "所有权只能转让给账号正常的有效成员"),
    SUPER_ADMIN_CANNOT_USE_WORKSPACE(HttpStatus.FORBIDDEN, "超级管理员不能使用普通空间功能"),
    INVITATION_NOT_FOUND(HttpStatus.NOT_FOUND, "邀请码不存在"),
    INVITATION_CHANGED(HttpStatus.CONFLICT, "邀请码已经变化，请刷新后重试"),
    INVITATION_REVOKED(HttpStatus.CONFLICT, "邀请码已停用"),
    INVITATION_EXPIRED(HttpStatus.CONFLICT, "邀请码已过期"),
    INVITATION_EXHAUSTED(HttpStatus.CONFLICT, "邀请码使用次数已达上限"),
    REMOVED_MEMBER_REQUIRES_NEW_INVITATION(HttpStatus.FORBIDDEN, "被移除成员需要使用移除后生成的新邀请码"),
    MARKET_QUOTE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "当前无法取得该股票的可用行情"),
    STOCK_NOT_AVAILABLE(HttpStatus.CONFLICT, "该股票当前不能开始新的研究周期"),
    CONCURRENT_OPERATION_CONFLICT(HttpStatus.CONFLICT, "并发操作冲突，请刷新后重试"),
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "服务器暂时无法处理请求");

    private final HttpStatus httpStatus;
    private final String message;

    ErrorCode(HttpStatus httpStatus, String message) {
        this.httpStatus = httpStatus;
        this.message = message;
    }

    public HttpStatus httpStatus() {
        return httpStatus;
    }

    public String message() {
        return message;
    }
}
