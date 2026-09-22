package com.stock.system.admin.service;

import com.stock.system.admin.dto.AdminUserQueryDTO;
import com.stock.system.admin.dto.AdminWorkspaceQueryDTO;
import com.stock.system.admin.dto.OperationLogQueryDTO;
import com.stock.system.admin.mapper.AdminQueryMapper;
import com.stock.system.admin.vo.AdminOperationLogResponseVO;
import com.stock.system.admin.vo.AdminUserDetailResponseVO;
import com.stock.system.admin.vo.AdminUserSummaryResponseVO;
import com.stock.system.admin.vo.AdminWorkspaceDetailResponseVO;
import com.stock.system.admin.vo.AdminWorkspaceSummaryResponseVO;
import com.stock.system.common.api.ErrorCode;
import com.stock.system.common.api.PageResponseVO;
import com.stock.system.common.exception.BusinessException;
import org.springframework.stereotype.Service;

import java.util.Set;

@Service
public class AdminQueryService {

    private static final Set<String> USER_STATUSES = Set.of("ACTIVE", "DISABLED");
    private static final Set<String> WORKSPACE_STATUSES = Set.of(
            "ACTIVE", "ARCHIVED_BY_OWNER", "ARCHIVED_BY_SUPER_ADMIN");
    private static final Set<String> ACTIONS = Set.of(
            "DISABLE_USER", "RESTORE_USER", "ARCHIVE_WORKSPACE", "RESTORE_WORKSPACE");
    private static final Set<String> RESULTS = Set.of("SUCCESS", "FAILED");

    private final AdminQueryMapper queryMapper;

    public AdminQueryService(AdminQueryMapper queryMapper) {
        this.queryMapper = queryMapper;
    }

    public PageResponseVO<AdminUserSummaryResponseVO> users(AdminUserQueryDTO query) {
        Page page = page(query.page(), query.size());
        String status = optionalEnum(query.status(), USER_STATUSES);
        String keyword = normalizeKeyword(query.keyword());
        long total = queryMapper.countUsers(status, keyword);
        return PageResponseVO.of(
                queryMapper.selectUsers(status, keyword, page.size(), page.offset()),
                page.number(), page.size(), total);
    }

    public AdminUserDetailResponseVO user(Long userId) {
        AdminUserSummaryResponseVO user = queryMapper.selectUser(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }
        return new AdminUserDetailResponseVO(
                user.userId(), user.username(), user.nickname(), user.status(), user.accountType(),
                user.workspaceCount(), user.ownedWorkspaceCount(), user.createdAt(),
                queryMapper.selectMemberships(userId));
    }

    public PageResponseVO<AdminWorkspaceSummaryResponseVO> workspaces(AdminWorkspaceQueryDTO query) {
        Page page = page(query.page(), query.size());
        String status = optionalEnum(query.status(), WORKSPACE_STATUSES);
        String keyword = normalizeKeyword(query.keyword());
        long total = queryMapper.countWorkspaces(status, keyword);
        return PageResponseVO.of(
                queryMapper.selectWorkspaces(status, keyword, page.size(), page.offset()),
                page.number(), page.size(), total);
    }

    public AdminWorkspaceDetailResponseVO workspace(Long workspaceId) {
        AdminWorkspaceSummaryResponseVO workspace = queryMapper.selectWorkspace(workspaceId);
        if (workspace == null) {
            throw new BusinessException(ErrorCode.WORKSPACE_NOT_FOUND);
        }
        return new AdminWorkspaceDetailResponseVO(
                workspace.workspaceId(), workspace.name(), workspace.status(),
                workspace.createdByUserId(), workspace.createdByUsername(),
                workspace.ownerUserId(), workspace.ownerUsername(), workspace.memberCount(),
                workspace.archivedAt(), workspace.createdAt(),
                queryMapper.selectWorkspaceMembers(workspaceId));
    }

    public PageResponseVO<AdminOperationLogResponseVO> operationLogs(OperationLogQueryDTO query) {
        Page page = page(query.page(), query.size());
        String action = optionalEnum(query.action(), ACTIONS);
        String result = optionalEnum(query.result(), RESULTS);
        long total = queryMapper.countOperationLogs(action, result);
        return PageResponseVO.of(
                queryMapper.selectOperationLogs(action, result, page.size(), page.offset()),
                page.number(), page.size(), total);
    }

    private Page page(int number, int size) {
        if (number < 1 || size < 1 || size > 100) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "页码必须大于0，每页数量必须在1到100之间");
        }
        return new Page(number, size, (number - 1) * size);
    }

    private String optionalEnum(String value, Set<String> allowed) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim().toUpperCase();
        if (!allowed.contains(normalized)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        return normalized;
    }

    private String normalizeKeyword(String keyword) {
        if (keyword == null || keyword.isBlank()) return null;
        String normalized = keyword.trim();
        if (normalized.codePointCount(0, normalized.length()) > 50) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "搜索关键词最多50个字符");
        }
        return normalized;
    }

    private record Page(int number, int size, int offset) {
    }
}
