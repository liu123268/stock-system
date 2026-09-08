package com.stock.system.auth.service;

import com.stock.system.auth.dto.LoginRequestDTO;
import com.stock.system.auth.dto.RegisterRequestDTO;
import com.stock.system.auth.vo.CurrentUserResponseVO;
import com.stock.system.auth.vo.LoginResponseVO;
import com.stock.system.auth.vo.RegisterResponseVO;
import com.stock.system.common.api.ErrorCode;
import com.stock.system.common.exception.BusinessException;
import com.stock.system.security.JwtUtil;
import com.stock.system.user.entity.AppUser;
import com.stock.system.user.service.UserService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

@Service
public class AuthService {

    private final UserService userService;
    private final PasswordEncoder passwordEncoder;

    public AuthService(UserService userService, PasswordEncoder passwordEncoder) {
        this.userService = userService;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public RegisterResponseVO register(RegisterRequestDTO request) {
        String username = normalizeUsername(request.username());

        if (userService.usernameExists(username)) {
            throw new BusinessException(ErrorCode.USERNAME_ALREADY_EXISTS);
        }

        AppUser user = new AppUser();
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setNickname(request.nickname().trim());
        user.setStatus("ACTIVE");

        try {
            userService.create(user);
        } catch (DuplicateKeyException exception) {
            // 先查询是为了给常见情况快速提示；唯一约束负责兜住并发注册。
            throw new BusinessException(ErrorCode.USERNAME_ALREADY_EXISTS);
        }

        return new RegisterResponseVO(user.getId(), user.getUsername(), user.getNickname());
    }

    public LoginResponseVO login(LoginRequestDTO request) {
        String username = normalizeUsername(request.username());
        AppUser user = userService.findByUsername(username);

        // 两种失败共用一个错误，避免泄露某个登录账号是否已经注册。
        if (user == null || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
        }
        if (!"ACTIVE".equals(user.getStatus())) {
            throw new BusinessException(ErrorCode.ACCOUNT_DISABLED);
        }

        Map<String, Object> claims = new HashMap<>();
        claims.put("userId", user.getId());
        claims.put("username", user.getUsername());
        String accessToken = JwtUtil.createToken(claims);

        return new LoginResponseVO(
                accessToken,
                "Bearer",
                JwtUtil.getExpireSeconds(),
                user.getId(),
                user.getUsername(),
                user.getNickname()
        );
    }

    public CurrentUserResponseVO getCurrentUser(Long userId) {
        AppUser user = userService.findById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        }
        if (!"ACTIVE".equals(user.getStatus())) {
            throw new BusinessException(ErrorCode.ACCOUNT_DISABLED);
        }

        return new CurrentUserResponseVO(user.getId(), user.getUsername(), user.getNickname());
    }

    private String normalizeUsername(String username) {
        return username.trim().toLowerCase(Locale.ROOT);
    }
}
