package com.demo.chat.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.demo.chat.user.entity.SysUserRole;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface SysUserRoleMapper extends BaseMapper<SysUserRole> {

    @Select("SELECT role_id FROM sys_user_role WHERE user_id = #{userId}")
    List<Long> selectRoleIdsByUserId(Long userId);

    /**
     * 根据角色 ID 查询关联的用户 ID 列表
     */
    List<Long> selectUserIdsByRoleId(@Param("roleId") Long roleId);

    /**
     * 根据用户 ID 删除关联记录
     */
    int deleteByUserId(@Param("userId") Long userId);

    /**
     * 根据角色 ID 删除关联记录
     */
    int deleteByRoleId(@Param("roleId") Long roleId);

    /**
     * 批量插入用户-角色关联
     */
    int batchInsert(@Param("list") List<SysUserRole> list);
}
