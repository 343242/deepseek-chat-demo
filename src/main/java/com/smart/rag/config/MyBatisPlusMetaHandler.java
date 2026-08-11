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
        this.strictInsertFill(metaObject, "updatedAt", OffsetDateTime.class, now);
        // 两套字段命名源于业务现实（非兼容层）：rag_document 表用 create_time/update_time，
        // 其余表用 created_at/updated_at。两套命名都需填充，否则逻辑删除/更新
        // 会以 null 覆盖 NOT NULL 列触发约束违约。
        this.strictInsertFill(metaObject, "createTime", OffsetDateTime.class, now);
        this.strictInsertFill(metaObject, "updateTime", OffsetDateTime.class, now);
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        OffsetDateTime now = OffsetDateTime.now();
        this.strictUpdateFill(metaObject, "updatedAt", OffsetDateTime.class, now);
        this.strictUpdateFill(metaObject, "updateTime", OffsetDateTime.class, now);
    }
}
