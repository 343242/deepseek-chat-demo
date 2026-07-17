package com.smart.rag.infrastructure.trace;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 链路追踪事件 Mapper
 * <p>
 * MyBatis-Plus BaseMapper 提供 insert/selectById 等 CRUD；
 * 自定义查询见 {@code resources/mapper/TraceMapper.xml}。
 */
@Mapper
public interface TraceMapper extends BaseMapper<TraceEvent> {

    /**
     * 按会话 ID 查询链路事件（按时间正序，还原请求完整流程）。
     */
    List<TraceEvent> selectBySessionId(@Param("sessionId") String sessionId);
}
