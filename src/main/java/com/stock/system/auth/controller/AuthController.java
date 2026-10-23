package com.stock.system.auth.controller;

import com.stock.system.auth.dto.LoginRequestDTO;
import com.stock.system.auth.dto.RegisterRequestDTO;
import com.stock.system.auth.dto.UpdateNicknameRequestDTO;
import com.stock.system.auth.dto.ChangePasswordRequestDTO;
import com.stock.system.auth.service.AuthService;
import com.stock.system.auth.vo.CurrentUserResponseVO;
import com.stock.system.auth.vo.LoginResponseVO;
import com.stock.system.auth.vo.RegisterResponseVO;
import com.stock.system.common.api.Result;
import com.stock.system.security.LoginUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@Tag(name = "01 账号与认证")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    @Operation(summary = "注册账号")
    @SecurityRequirements
    @ResponseStatus(HttpStatus.CREATED)
    public Result<RegisterResponseVO> register(@Valid @RequestBody RegisterRequestDTO request) {
        return Result.success(authService.register(request));
    }

    @PostMapping("/login")
    @Operation(summary = "登录并取得 JWT")
    @ApiResponse(responseCode = "401", description = "账号或密码错误，或账号已不可用；使用统一错误外壳。",
            ref = "#/components/responses/LoginRejected")
    @SecurityRequirements
    public Result<LoginResponseVO> login(@Valid @RequestBody LoginRequestDTO request) {
        return Result.success(authService.login(request));
    }

    @GetMapping("/me")
    @Operation(summary = "读取当前账号")
    public Result<CurrentUserResponseVO> me(@AuthenticationPrincipal LoginUser currentUser) {
        return Result.success(authService.getCurrentUser(currentUser.userId()));
    }

    @PatchMapping("/me")
    @Operation(summary = "修改当前账号昵称")
    public Result<CurrentUserResponseVO> updateNickname(
            @AuthenticationPrincipal LoginUser currentUser,
            @Valid @RequestBody UpdateNicknameRequestDTO request
    ) {
        return Result.success(authService.updateNickname(currentUser.userId(), request.nickname()));
    }

    @PostMapping("/password")
    @Operation(summary = "修改密码并使旧凭证失效")
    public Result<Void> changePassword(
            @AuthenticationPrincipal LoginUser currentUser,
            @Valid @RequestBody ChangePasswordRequestDTO request
    ) {
        authService.changePassword(currentUser.userId(), request.currentPassword(), request.newPassword());
        return Result.success(null);
    }
}
