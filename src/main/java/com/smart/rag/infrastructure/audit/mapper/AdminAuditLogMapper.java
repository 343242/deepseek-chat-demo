package com.smart.rag.infrastructure.audit.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smart.rag.infrastructure.audit.entity.AdminAuditLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface AdminAuditLogMapper extends BaseMapper<AdminAuditLog> {

    List<AdminAuditLog> selectByResource(@Param("resourceType") String resourceType,
                                          @Param("resourceId") String resourceId,
                                          @Param("limit") int limit);

    List<AdminAuditLog> selectByOperator(@Param("operatorId") Long operatorId,
                                          @Param("limit") int limit);
}
