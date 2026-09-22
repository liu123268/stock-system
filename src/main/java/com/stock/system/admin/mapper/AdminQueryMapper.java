package com.stock.system.admin.mapper;

import com.stock.system.admin.vo.AdminMembershipResponseVO;
import com.stock.system.admin.vo.AdminOperationLogResponseVO;
import com.stock.system.admin.vo.AdminUserSummaryResponseVO;
import com.stock.system.admin.vo.AdminWorkspaceMemberResponseVO;
import com.stock.system.admin.vo.AdminWorkspaceSummaryResponseVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface AdminQueryMapper {

    @Select("""
            <script>
            SELECT u.id AS userId,
                   u.username,
                   u.nickname,
                   u.status,
                   CASE WHEN sa.user_id IS NULL THEN 'REGULAR' ELSE 'SUPER_ADMIN' END AS accountType,
                   COUNT(DISTINCT CASE WHEN wm.status = 'ACTIVE' THEN wm.workspace_id END) AS workspaceCount,
                   COUNT(DISTINCT owned.id) AS ownedWorkspaceCount,
                   u.created_at AS createdAt
            FROM app_user u
            LEFT JOIN super_admin sa ON sa.user_id = u.id
            LEFT JOIN workspace_member wm ON wm.user_id = u.id
            LEFT JOIN workspace owned ON owned.owner_user_id = u.id
            WHERE 1 = 1
            <if test="status != null and status != ''">
              AND u.status = #{status}
            </if>
            <if test="keyword != null and keyword != ''">
              AND (LOCATE(#{keyword}, u.username) &gt; 0 OR LOCATE(#{keyword}, u.nickname) &gt; 0)
            </if>
            GROUP BY u.id, u.username, u.nickname, u.status, sa.user_id, u.created_at
            ORDER BY u.created_at DESC, u.id DESC
            LIMIT #{size} OFFSET #{offset}
            </script>
            """)
    List<AdminUserSummaryResponseVO> selectUsers(
            @Param("status") String status,
            @Param("keyword") String keyword,
            @Param("size") int size,
            @Param("offset") int offset
    );

    @Select("""
            <script>
            SELECT COUNT(*) FROM app_user u
            WHERE 1 = 1
            <if test="status != null and status != ''">
              AND u.status = #{status}
            </if>
            <if test="keyword != null and keyword != ''">
              AND (LOCATE(#{keyword}, u.username) &gt; 0 OR LOCATE(#{keyword}, u.nickname) &gt; 0)
            </if>
            </script>
            """)
    long countUsers(@Param("status") String status, @Param("keyword") String keyword);

    @Select("""
            SELECT u.id AS userId,
                   u.username,
                   u.nickname,
                   u.status,
                   CASE WHEN sa.user_id IS NULL THEN 'REGULAR' ELSE 'SUPER_ADMIN' END AS accountType,
                   COUNT(DISTINCT CASE WHEN wm.status = 'ACTIVE' THEN wm.workspace_id END) AS workspaceCount,
                   COUNT(DISTINCT owned.id) AS ownedWorkspaceCount,
                   u.created_at AS createdAt
            FROM app_user u
            LEFT JOIN super_admin sa ON sa.user_id = u.id
            LEFT JOIN workspace_member wm ON wm.user_id = u.id
            LEFT JOIN workspace owned ON owned.owner_user_id = u.id
            WHERE u.id = #{userId}
            GROUP BY u.id, u.username, u.nickname, u.status, sa.user_id, u.created_at
            """)
    AdminUserSummaryResponseVO selectUser(@Param("userId") Long userId);

    @Select("""
            SELECT w.id AS workspaceId,
                   w.name AS workspaceName,
                   w.status AS workspaceStatus,
                   CASE WHEN w.owner_user_id = m.user_id THEN 'OWNER' ELSE 'MEMBER' END AS role,
                   m.status AS membershipStatus,
                   m.first_joined_at AS firstJoinedAt,
                   m.last_joined_at AS lastJoinedAt,
                   m.last_inactive_at AS lastInactiveAt
            FROM workspace_member m
            JOIN workspace w ON w.id = m.workspace_id
            WHERE m.user_id = #{userId}
            ORDER BY m.last_joined_at DESC, m.id DESC
            """)
    List<AdminMembershipResponseVO> selectMemberships(@Param("userId") Long userId);

    @Select("""
            <script>
            SELECT w.id AS workspaceId,
                   w.name,
                   w.status,
                   w.created_by_user_id AS createdByUserId,
                   creator.username AS createdByUsername,
                   w.owner_user_id AS ownerUserId,
                   owner.username AS ownerUsername,
                   COUNT(CASE WHEN m.status = 'ACTIVE' THEN 1 END) AS memberCount,
                   w.archived_at AS archivedAt,
                   w.created_at AS createdAt
            FROM workspace w
            JOIN app_user creator ON creator.id = w.created_by_user_id
            JOIN app_user owner ON owner.id = w.owner_user_id
            LEFT JOIN workspace_member m ON m.workspace_id = w.id
            WHERE 1 = 1
            <if test="status != null and status != ''">
              AND w.status = #{status}
            </if>
            <if test="keyword != null and keyword != ''">
              AND LOCATE(#{keyword}, w.name) &gt; 0
            </if>
            GROUP BY w.id, w.name, w.status, w.created_by_user_id, creator.username,
                     w.owner_user_id, owner.username, w.archived_at, w.created_at
            ORDER BY w.created_at DESC, w.id DESC
            LIMIT #{size} OFFSET #{offset}
            </script>
            """)
    List<AdminWorkspaceSummaryResponseVO> selectWorkspaces(
            @Param("status") String status,
            @Param("keyword") String keyword,
            @Param("size") int size,
            @Param("offset") int offset
    );

    @Select("""
            <script>
            SELECT COUNT(*) FROM workspace w
            WHERE 1 = 1
            <if test="status != null and status != ''">
              AND w.status = #{status}
            </if>
            <if test="keyword != null and keyword != ''">
              AND LOCATE(#{keyword}, w.name) &gt; 0
            </if>
            </script>
            """)
    long countWorkspaces(@Param("status") String status, @Param("keyword") String keyword);

    @Select("""
            SELECT w.id AS workspaceId,
                   w.name,
                   w.status,
                   w.created_by_user_id AS createdByUserId,
                   creator.username AS createdByUsername,
                   w.owner_user_id AS ownerUserId,
                   owner.username AS ownerUsername,
                   COUNT(CASE WHEN m.status = 'ACTIVE' THEN 1 END) AS memberCount,
                   w.archived_at AS archivedAt,
                   w.created_at AS createdAt
            FROM workspace w
            JOIN app_user creator ON creator.id = w.created_by_user_id
            JOIN app_user owner ON owner.id = w.owner_user_id
            LEFT JOIN workspace_member m ON m.workspace_id = w.id
            WHERE w.id = #{workspaceId}
            GROUP BY w.id, w.name, w.status, w.created_by_user_id, creator.username,
                     w.owner_user_id, owner.username, w.archived_at, w.created_at
            """)
    AdminWorkspaceSummaryResponseVO selectWorkspace(@Param("workspaceId") Long workspaceId);

    @Select("""
            SELECT m.user_id AS userId,
                   u.username,
                   u.nickname,
                   u.status AS accountStatus,
                   CASE WHEN w.owner_user_id = m.user_id THEN 'OWNER' ELSE 'MEMBER' END AS role,
                   m.status AS membershipStatus,
                   m.first_joined_at AS firstJoinedAt,
                   m.last_joined_at AS lastJoinedAt,
                   m.last_inactive_at AS lastInactiveAt
            FROM workspace_member m
            JOIN app_user u ON u.id = m.user_id
            JOIN workspace w ON w.id = m.workspace_id
            WHERE m.workspace_id = #{workspaceId}
            ORDER BY CASE WHEN w.owner_user_id = m.user_id THEN 0 ELSE 1 END,
                     m.first_joined_at,
                     m.user_id
            """)
    List<AdminWorkspaceMemberResponseVO> selectWorkspaceMembers(
            @Param("workspaceId") Long workspaceId
    );

    @Select("""
            <script>
            SELECT l.id AS operationLogId,
                   l.action,
                   l.target_id AS targetId,
                   CASE
                     WHEN l.action IN ('DISABLE_USER', 'RESTORE_USER') THEN user_target.username
                     ELSE workspace_target.name
                   END AS targetLabel,
                   l.before_status AS beforeStatus,
                   l.after_status AS afterStatus,
                   l.result,
                   l.created_at AS createdAt
            FROM super_admin_operation_log l
            LEFT JOIN app_user user_target
              ON l.action IN ('DISABLE_USER', 'RESTORE_USER') AND user_target.id = l.target_id
            LEFT JOIN workspace workspace_target
              ON l.action IN ('ARCHIVE_WORKSPACE', 'RESTORE_WORKSPACE')
             AND workspace_target.id = l.target_id
            WHERE 1 = 1
            <if test="action != null and action != ''">
              AND l.action = #{action}
            </if>
            <if test="result != null and result != ''">
              AND l.result = #{result}
            </if>
            ORDER BY l.created_at DESC, l.id DESC
            LIMIT #{size} OFFSET #{offset}
            </script>
            """)
    List<AdminOperationLogResponseVO> selectOperationLogs(
            @Param("action") String action,
            @Param("result") String result,
            @Param("size") int size,
            @Param("offset") int offset
    );

    @Select("""
            <script>
            SELECT COUNT(*) FROM super_admin_operation_log l
            WHERE 1 = 1
            <if test="action != null and action != ''">
              AND l.action = #{action}
            </if>
            <if test="result != null and result != ''">
              AND l.result = #{result}
            </if>
            </script>
            """)
    long countOperationLogs(@Param("action") String action, @Param("result") String result);
}
