package com.stock.system.workspace.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.stock.system.workspace.entity.Workspace;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface WorkspaceMapper extends BaseMapper<Workspace> {

    @Select("SELECT * FROM workspace WHERE id = #{workspaceId} FOR UPDATE")
    Workspace selectByIdForUpdate(@Param("workspaceId") Long workspaceId);

    @Update("UPDATE workspace SET status = #{status}, archived_at = #{archivedAt} WHERE id = #{workspaceId}")
    int updateArchiveState(
            @Param("workspaceId") Long workspaceId,
            @Param("status") String status,
            @Param("archivedAt") LocalDateTime archivedAt
    );
}
