package com.stock.system.auth.controller;

import com.stock.system.auth.dto.LoginRequestDTO;
import com.stock.system.auth.dto.RegisterRequestDTO;
import com.stock.system.auth.service.AuthService;
import com.stock.system.auth.vo.CurrentUserResponseVO;
import com.stock.system.auth.vo.LoginResponseVO;
import com.stock.system.auth.vo.RegisterResponseVO;
import com.stock.system.common.api.Result;
import com.stock.system.security.LoginUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public Result<RegisterResponseVO> register(@Valid @RequestBody RegisterRequestDTO request) {
        return Result.success(authService.register(request));
    }

    @PostMapping("/login")
    public Result<LoginResponseVO> login(@Valid @RequestBody LoginRequestDTO request) {
        return Result.success(authService.login(request));
    }

    @GetMapping("/me")
    public Result<CurrentUserResponseVO> me(@AuthenticationPrincipal LoginUser currentUser) {
        return Result.success(authService.getCurrentUser(currentUser.userId()));
    }
}
