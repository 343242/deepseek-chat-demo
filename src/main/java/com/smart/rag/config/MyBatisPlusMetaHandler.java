package com.smart.rag.config;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

@Component
public class MyBatisPlusMetaHandler implements MetaObjectHandler {

    @Override
    public void insertFill(MetaObject metaObject) {
        // createdAt 与 updatedAt 同源，避免两次 now() 产生微小差异
        OffsetDateTime now = OffsetDateTime.now();
        this.strictInsertFill(metaObject, "createdAt", OffsetDateTime.class, now);
        // updatedAt 标的是 FieldFill.INSERT_UPDATE，insert 时必须一并填充；
        // 否则 MyBatis-Plus 会把该字段以 null 纳入 INSERT，覆盖 DB 的 DEFAULT now() 而触发 NOT NULL 违约
        this.strictInsertFill(metaObject, "updatedAt", OffsetDateTime.class, now);
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        this.strictUpdateFill(metaObject, "updatedAt", OffsetDateTime.class, OffsetDateTime.now());
    }
}
