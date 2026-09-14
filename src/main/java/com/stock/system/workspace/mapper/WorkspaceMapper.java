package com.stock.system.workspace.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.stock.system.workspace.entity.Workspace;
import com.stock.system.workspace.vo.WorkspaceSummaryResponseVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface WorkspaceMapper extends BaseMapper<Workspace> {

    @Select("SELECT * FROM workspace WHERE id = #{workspaceId} FOR UPDATE")
    Workspace selectByIdForUpdate(@Param("workspaceId") Long workspaceId);

    @Select("""
            SELECT w.id AS workspaceId,
                   w.name,
                   CASE WHEN w.owner_user_id = viewer.user_id THEN 'OWNER' ELSE 'MEMBER' END AS role,
                   w.status,
                   COUNT(active_member.id) AS memberCount,
                   w.created_at AS createdAt
            FROM workspace_member viewer
            JOIN workspace w ON w.id = viewer.workspace_id
            LEFT JOIN workspace_member active_member
              ON active_member.workspace_id = w.id AND active_member.status = 'ACTIVE'
            WHERE viewer.user_id = #{userId}
              AND viewer.status = 'ACTIVE'
            GROUP BY w.id, w.name, w.owner_user_id, viewer.user_id, w.status, w.created_at
            ORDER BY w.created_at DESC, w.id DESC
            """)
    List<WorkspaceSummaryResponseVO> selectSummariesForUser(@Param("userId") Long userId);

    @Update("UPDATE workspace SET status = #{status}, archived_at = #{archivedAt} WHERE id = #{workspaceId}")
    int updateArchiveState(
            @Param("workspaceId") Long workspaceId,
            @Param("status") String status,
            @Param("archivedAt") LocalDateTime archivedAt
    );
}
