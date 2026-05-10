package com.demo.chat.rag.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.demo.chat.rag.entity.RagDocument;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface RagDocumentMapper extends BaseMapper<RagDocument> {
}
