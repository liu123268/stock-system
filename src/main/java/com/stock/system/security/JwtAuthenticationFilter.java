package com.stock.system.security;

import com.stock.system.admin.service.SuperAdminService;
import com.stock.system.common.api.ErrorCode;
import com.stock.system.user.entity.AppUser;
import com.stock.system.user.service.UserService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
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
    private final JwtUtil jwtUtil;
    private final UserService userService;
    private final SuperAdminService superAdminService;

    public JwtAuthenticationFilter(
            RestAuthenticationEntryPoint authenticationEntryPoint,
            JwtUtil jwtUtil,
            UserService userService,
            SuperAdminService superAdminService
    ) {
        this.authenticationEntryPoint = authenticationEntryPoint;
        this.jwtUtil = jwtUtil;
        this.userService = userService;
        this.superAdminService = superAdminService;
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
            if (principal == null) {
                authenticationEntryPoint.write(response, ErrorCode.ACCOUNT_DISABLED);
                return;
            }
            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(
                            principal,
                            null,
                            List.of(new SimpleGrantedAuthority("ROLE_" + principal.accountType()))
                    );
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

        Claims claims = jwtUtil.parseToken(token);
        Number userIdClaim = claims.get("userId", Number.class);
        if (userIdClaim == null) {
            throw new IllegalArgumentException("Token 缺少用户身份");
        }
        AppUser user = userService.findById(userIdClaim.longValue());
        if (user == null) {
            throw new IllegalArgumentException("Token 对应用户不存在");
        }
        if (!"ACTIVE".equals(user.getStatus())) {
            return null;
        }
        Number credentialVersionClaim = claims.get("credentialVersion", Number.class);
        long tokenVersion = credentialVersionClaim == null ? 0 : credentialVersionClaim.longValue();
        if (tokenVersion != user.getCredentialVersion()) {
            throw new IllegalArgumentException("Token 凭据版本已失效");
        }
        String accountType = superAdminService.isSuperAdmin(user.getId())
                ? "SUPER_ADMIN"
                : "REGULAR";
        return new LoginUser(user.getId(), user.getUsername(), accountType);
    }
}
