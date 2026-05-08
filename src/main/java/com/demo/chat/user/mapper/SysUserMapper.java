package com.demo.chat.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.demo.chat.user.entity.SysUser;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SysUserMapper extends BaseMapper<SysUser> {
}
