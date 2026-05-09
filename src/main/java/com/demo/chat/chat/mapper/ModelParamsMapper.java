package com.demo.chat.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.demo.chat.chat.entity.ModelParams;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 模型参数 Mapper
 * <p>
 * 封装所有数据库查询逻辑，Service 层不直接使用 LambdaQueryWrapper。
 */
@Mapper
public interface ModelParamsMapper extends BaseMapper<ModelParams> {

    ModelParams selectByModelId(@Param("modelId") String modelId);

    List<ModelParams> selectAllOrdered();

    int deleteByModelId(@Param("modelId") String modelId);
}
