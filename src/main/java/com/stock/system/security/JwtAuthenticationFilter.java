package com.stock.system.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * 每个 HTTP 请求只执行一次的 JWT 验证过滤器。
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final RestAuthenticationEntryPoint authenticationEntryPoint;

    public JwtAuthenticationFilter(RestAuthenticationEntryPoint authenticationEntryPoint) {
        this.authenticationEntryPoint = authenticationEntryPoint;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = authorization.substring(BEARER_PREFIX.length()).trim();
        try {
            LoginUser principal = parsePrincipal(token);
            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(principal, null, List.of());
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authentication);
            filterChain.doFilter(request, response);
        } catch (JwtException | IllegalArgumentException exception) {
            SecurityContextHolder.clearContext();
            authenticationEntryPoint.commence(request, response, null);
        }
    }

    private LoginUser parsePrincipal(String token) {
        if (token.isBlank()) {
            throw new IllegalArgumentException("Bearer Token 不能为空");
        }

        Claims claims = JwtUtil.parseToken(token);
        Number userIdClaim = claims.get("userId", Number.class);
        String username = claims.get("username", String.class);
        if (userIdClaim == null || username == null || username.isBlank()) {
            throw new IllegalArgumentException("Token 缺少用户身份");
        }
        return new LoginUser(userIdClaim.longValue(), username);
    }
}
