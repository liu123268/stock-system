package com.stock.system.common.api;

import org.springframework.http.HttpStatus;

/**
 * 程序可以稳定识别的错误，以及每类错误默认对应的 HTTP 状态。
 */
public enum ErrorCode {
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "请求参数不合法"),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "未登录或登录已过期"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "当前账号无权执行该操作"),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "登录账号或密码错误"),
    CURRENT_PASSWORD_INCORRECT(HttpStatus.BAD_REQUEST, "原密码不正确"),
    ACCOUNT_DISABLED(HttpStatus.UNAUTHORIZED, "账号当前不可用"),
    USERNAME_ALREADY_EXISTS(HttpStatus.CONFLICT, "登录账号已存在"),
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "用户不存在"),
    SUPER_ADMIN_CANNOT_DISABLE_SELF(HttpStatus.CONFLICT, "唯一超级管理员不能禁用自己的账号"),
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
    INVITATION_ATTEMPTS_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "邀请码尝试次数过多，请稍后再试"),
    REMOVED_MEMBER_REQUIRES_NEW_INVITATION(HttpStatus.FORBIDDEN, "被移除成员需要使用移除后生成的新邀请码"),
    MARKET_QUOTE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "当前无法取得该股票的可用行情"),
    MARKET_KLINE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "当前无法取得该股票的 K 线数据"),
    MARKET_KLINE_LIMIT_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS,
            "Longbridge 返回 301607：K 线请求触发接口限制，请减少数据量或核对本月标的额度"),
    MARKET_INTRADAY_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE,
            "当前无法取得该股票在所选日期的分时数据"),
    MARKET_INTRADAY_HISTORY_LIMIT_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS,
            "Longbridge 返回 301607：历史分时需要分钟 K 线，本月历史行情标的额度已触发限制"),
    MARKET_INTRADAY_HISTORY_RANGE_EXCEEDED(HttpStatus.UNPROCESSABLE_ENTITY,
            "所选日期超出当前账户可查看的历史分钟行情范围"),
    MARKET_INTRADAY_NON_TRADING_DAY(HttpStatus.BAD_REQUEST,
            "所选日期不是该市场的交易日"),
    STOCK_NOT_FOUND(HttpStatus.NOT_FOUND, "股票不存在或不在当前行情范围"),
    WORKSPACE_STOCK_NOT_FOUND(HttpStatus.NOT_FOUND, "当前空间中不存在这只共享股票"),
    RESEARCH_CYCLE_NOT_FOUND(HttpStatus.NOT_FOUND, "当前空间股票中不存在该研究周期"),
    ACTIVE_RESEARCH_CYCLE_NOT_FOUND(HttpStatus.CONFLICT, "这只股票当前没有活动研究周期"),
    RESEARCH_CYCLE_CHANGED(HttpStatus.CONFLICT, "研究周期已经变化，请刷新后重试"),
    STOCK_OPINION_NOT_FOUND(HttpStatus.NOT_FOUND, "观点不存在"),
    STOCK_OPINION_ACCESS_DENIED(HttpStatus.FORBIDDEN, "只能撤回自己的观点"),
    STOCK_OPINION_ALREADY_WITHDRAWN(HttpStatus.CONFLICT, "观点已经撤回"),
    STOCK_OPINION_WITHDRAW_WINDOW_EXPIRED(HttpStatus.CONFLICT,
            "观点发表已超过 5 分钟，请新增观点说明变化或更正"),
    STOCK_NOT_AVAILABLE(HttpStatus.CONFLICT, "该股票当前不能开始新的研究周期"),
    STOCK_STATUS_UNAVAILABLE(HttpStatus.CONFLICT, "该股票交易状态暂时无法核实，不能开始新的研究周期"),
    CONCURRENT_OPERATION_CONFLICT(HttpStatus.CONFLICT, "并发操作冲突，请刷新后重试"),
    RESEARCH_AGENT_DISABLED(HttpStatus.SERVICE_UNAVAILABLE, "研究助手尚未启用"),
    RESEARCH_AGENT_BUDGET_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, "研究助手的可用预算不足，请联系维护者"),
    RESEARCH_AGENT_BUSY(HttpStatus.TOO_MANY_REQUESTS, "已有研究梳理正在进行，请等待后查看结果"),
    RESEARCH_AGENT_REQUEST_CONFLICT(HttpStatus.CONFLICT, "这次请求标识已用于其他内容，请重新发起"),
    RESEARCH_AGENT_RUN_NOT_FOUND(HttpStatus.NOT_FOUND, "研究梳理记录不存在"),
    RESEARCH_AGENT_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, "研究梳理已超时，请查看运行状态后再决定是否重试"),
    RESEARCH_AGENT_CLEARED(HttpStatus.CONFLICT, "个人记录已清空，本次结果不再保存"),
    RESEARCH_AGENT_SOURCE_LIMIT_EXCEEDED(HttpStatus.UNPROCESSABLE_ENTITY, "研究资料超出本次范围上限，请缩小范围"),
    RESEARCH_AGENT_SOURCES_CHANGED(HttpStatus.CONFLICT, "本次使用的研究资料已经失效或变化，请重新梳理"),
    RESEARCH_AGENT_INVALID_RESULT(HttpStatus.BAD_GATEWAY, "研究助手未返回可核对的完整结果"),
    RESEARCH_AGENT_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "研究助手暂时无法完成梳理，请稍后重试"),
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
