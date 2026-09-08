package com.stock.system.security;

/**
 * 已通过 JWT 验证的最小用户身份。
 *
 * JWT 中只保存稳定且非敏感的身份字段；昵称等可变资料由业务接口查询数据库。
 */
public record LoginUser(Long userId, String username) {
}
