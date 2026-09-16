package com.stock.system.workspace.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.stock.system.workspace.entity.WorkspaceMember;
import com.stock.system.workspace.vo.WorkspaceMemberResponseVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface WorkspaceMemberMapper extends BaseMapper<WorkspaceMember> {

    @Select("""
            SELECT * FROM workspace_member
            WHERE workspace_id = #{workspaceId} AND user_id = #{userId}
            FOR UPDATE
            """)
    WorkspaceMember selectForUpdate(
            @Param("workspaceId") Long workspaceId,
            @Param("userId") Long userId
    );

    @Select("""
            SELECT MAX(last_inactive_at) FROM workspace_member
            WHERE workspace_id = #{workspaceId} AND status = 'REMOVED'
            """)
    LocalDateTime selectLatestRemovedAt(@Param("workspaceId") Long workspaceId);

    @Select("""
            SELECT m.user_id AS userId,
                   u.username,
                   u.nickname,
                   CASE WHEN w.owner_user_id = m.user_id THEN 'OWNER' ELSE 'MEMBER' END AS role,
                   m.status,
                   m.first_joined_at AS firstJoinedAt,
                   m.last_joined_at AS lastJoinedAt
            FROM workspace_member m
            JOIN app_user u ON u.id = m.user_id
            JOIN workspace w ON w.id = m.workspace_id
            WHERE m.workspace_id = #{workspaceId}
              AND m.status = 'ACTIVE'
              AND EXISTS (
                  SELECT 1 FROM workspace_member viewer
                  WHERE viewer.workspace_id = m.workspace_id
                    AND viewer.user_id = #{viewerUserId}
                    AND viewer.status = 'ACTIVE'
              )
            ORDER BY CASE WHEN w.owner_user_id = m.user_id THEN 0 ELSE 1 END,
                     m.first_joined_at,
                     m.user_id
            """)
    List<WorkspaceMemberResponseVO> selectActiveMembers(
            @Param("workspaceId") Long workspaceId,
            @Param("viewerUserId") Long viewerUserId
    );
}
