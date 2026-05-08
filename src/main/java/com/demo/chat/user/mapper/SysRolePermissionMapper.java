package com.demo.chat.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.demo.chat.user.entity.SysRolePermission;
import com.demo.chat.user.entity.SysPermission;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface SysRolePermissionMapper extends BaseMapper<SysRolePermission> {

    @Select("""
        SELECT p.* FROM sys_permission p
        INNER JOIN sys_role_permission rp ON p.id = rp.permission_id
        WHERE rp.role_id = #{roleId} AND p.status = 1 AND p.deleted = 0
    """)
    List<SysPermission> selectPermissionsByRoleId(Long roleId);

    /**
     * 批量查询多个角色的权限，消除 N+1 查询
     */
    List<SysPermission> selectPermissionsByRoleIds(@Param("roleIds") List<Long> roleIds);
}
