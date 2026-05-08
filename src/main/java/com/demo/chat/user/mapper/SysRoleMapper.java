package com.demo.chat.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.demo.chat.user.entity.SysRole;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Optional;

@Mapper
public interface SysRoleMapper extends BaseMapper<SysRole> {

    /**
     * 查询所有未删除的角色，按 ID 升序
     */
    List<SysRole> selectAllOrdered();

    /**
     * 根据角色名查询未删除的角色
     */
    Optional<SysRole> selectByRoleName(@Param("roleName") String roleName);
}
