package com.smart.rag.mcp.admin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smart.rag.mcp.admin.entity.McpSecurityConfig;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface McpSecurityConfigMapper extends BaseMapper<McpSecurityConfig> {

    /** 单行表，永远 id=1 */
    McpSecurityConfig selectSingleton();

    int updateConfigJson(@Param("configJson") String configJson);
}
