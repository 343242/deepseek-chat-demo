package com.smart.rag.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smart.rag.user.entity.SysPermission;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Optional;

@Mapper
public interface SysPermissionMapper extends BaseMapper<SysPermission> {

    /**
     * 查询所有未删除的权限，按 ID 升序
     */
    List<SysPermission> selectAllOrdered();

    /**
     * 根据权限名称查询未删除的权限
     */
    Optional<SysPermission> selectByPermissionName(@Param("permissionName") String permissionName);

    /**
     * 根据资源标识查询未删除的权限
     */
    Optional<SysPermission> selectByResourceKey(@Param("resourceKey") String resourceKey);
}
