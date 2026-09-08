package com.stock.system.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("super_admin")
public class SuperAdmin {

    @TableId(type = IdType.INPUT)
    private Integer id;

    private Long userId;
}
