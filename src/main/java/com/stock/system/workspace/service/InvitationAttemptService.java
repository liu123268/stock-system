package com.stock.system.workspace.service;

import com.stock.system.common.api.ErrorCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/** 与加入空间共用事务：失败提交计数，成功清除计数。 */
@Service
public class InvitationAttemptService {

    private static final int MAX_FAILURES = 5;
    private static final int WINDOW_MINUTES = 15;

    private final JdbcTemplate jdbcTemplate;

    public InvitationAttemptService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void reserve(Long userId) {
        LocalDateTime now = LocalDateTime.now();
        // 唯一键使同一账号的并发尝试排队；随后锁定该计数行直至加入事务结束。
        jdbcTemplate.update("""
                INSERT INTO invitation_join_attempt (user_id, failure_count, window_started_at)
                VALUES (?, 0, ?)
                ON DUPLICATE KEY UPDATE user_id = user_id
                """, userId, now);
        Attempt attempt = jdbcTemplate.queryForObject("""
                SELECT failure_count, window_started_at
                FROM invitation_join_attempt WHERE user_id = ? FOR UPDATE
                """, (result, row) -> new Attempt(
                result.getInt("failure_count"),
                result.getTimestamp("window_started_at").toLocalDateTime()
        ), userId);
        if (attempt == null) {
            throw new IllegalStateException("邀请码尝试记录不存在");
        }
        if (!now.isBefore(attempt.windowStartedAt().plusMinutes(WINDOW_MINUTES))) {
            jdbcTemplate.update("""
                    UPDATE invitation_join_attempt SET failure_count = 0, window_started_at = ?
                    WHERE user_id = ?
                    """, now, userId);
        } else if (attempt.failureCount() >= MAX_FAILURES) {
            throw new InvitationAttemptRejectedException(ErrorCode.INVITATION_ATTEMPTS_LIMITED);
        }
        jdbcTemplate.update("""
                UPDATE invitation_join_attempt SET failure_count = failure_count + 1
                WHERE user_id = ?
                """, userId);
    }

    public void clear(Long userId) {
        jdbcTemplate.update("DELETE FROM invitation_join_attempt WHERE user_id = ?", userId);
    }

    private record Attempt(int failureCount, LocalDateTime windowStartedAt) {
    }
}
