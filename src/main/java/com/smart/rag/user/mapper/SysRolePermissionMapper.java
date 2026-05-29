package com.smart.rag.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smart.rag.user.entity.SysRolePermission;
import com.smart.rag.user.entity.SysPermission;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface SysRolePermissionMapper extends BaseMapper<SysRolePermission> {

    List<SysPermission> selectPermissionsByRoleId(Long roleId);

    /**
     * 批量查询多个角色的权限，消除 N+1 查询
     */
    List<SysPermission> selectPermissionsByRoleIds(@Param("roleIds") List<Long> roleIds);

    /**
     * 根据角色 ID 删除关联记录
     */
    int deleteByRoleId(@Param("roleId") Long roleId);

    /**
     * 批量插入角色-权限关联
     */
    int batchInsert(@Param("list") List<SysRolePermission> list);
}
