package com.demo.chat.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.demo.chat.user.entity.SysUser;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Optional;

@Mapper
public interface SysUserMapper extends BaseMapper<SysUser> {

    /**
     * 根据用户名查询未删除的用户
     */
    Optional<SysUser> selectByUsername(@Param("username") String username);

    /**
     * 根据用户 ID 查询未删除的用户
     */
    Optional<SysUser> selectActiveById(@Param("id") Long id);

    /**
     * 根据邮箱查询未删除的用户（排除指定 ID）
     */
    Optional<SysUser> selectByEmailExcludingId(@Param("email") String email, @Param("excludeId") Long excludeId);
}
