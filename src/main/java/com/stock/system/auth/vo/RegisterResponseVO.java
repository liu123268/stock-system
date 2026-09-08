package com.stock.system.auth.vo;

/**
 * 注册响应 VO：只把页面需要且可以公开的信息返回给前端。
 */
public record RegisterResponseVO(Long userId, String username, String nickname) {
}
