package com.stock.system.auth.vo;

/**
 * 登录响应 VO：返回页面后续访问所需的令牌和基础用户信息。
 */
public record LoginResponseVO(
        String accessToken,
        String tokenType,
        long expiresInSeconds,
        Long userId,
        String username,
        String nickname
) {
}
