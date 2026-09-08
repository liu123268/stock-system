package com.stock.system.workspace.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.stock.system.workspace.entity.WorkspaceInvitation;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface WorkspaceInvitationMapper extends BaseMapper<WorkspaceInvitation> {

    @Select("""
            SELECT * FROM workspace_invitation
            WHERE workspace_id = #{workspaceId} AND status = 'ACTIVE'
            LIMIT 1 FOR UPDATE
            """)
    WorkspaceInvitation selectCurrentForUpdate(@Param("workspaceId") Long workspaceId);

    @Select("SELECT * FROM workspace_invitation WHERE id = #{invitationId} FOR UPDATE")
    WorkspaceInvitation selectByIdForUpdate(@Param("invitationId") Long invitationId);
}
