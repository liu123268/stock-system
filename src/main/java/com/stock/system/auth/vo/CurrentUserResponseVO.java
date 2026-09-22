package com.stock.system.auth.vo;

/**
 * 当前用户响应：返回数据库中的最新公开资料，不暴露密码摘要和账号内部字段。
 */
public record CurrentUserResponseVO(
        Long userId,
        String username,
        String nickname,
        String accountType
) {
}
