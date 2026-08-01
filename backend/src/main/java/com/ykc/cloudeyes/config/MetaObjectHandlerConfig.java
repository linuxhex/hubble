package com.ykc.cloudeyes.config;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * MyBatis Plus自动填充配置
 *
 * @author Cloud Eyes Team
 */
@Component
public class MetaObjectHandlerConfig implements MetaObjectHandler {

    /**
     * 自动填充创建时间
     */
    @Override
    public void insertFill(MetaObject metaObject) {
        // MyBatis Plus 3.5.12 兼容写法
        this.strictInsertFill(metaObject, "createdAt", LocalDateTime.class, LocalDateTime.now());
        this.strictInsertFill(metaObject, "updatedAt", LocalDateTime.class, LocalDateTime.now());
    }

    /**
     * 自动填充更新时间
     */
    @Override
    public void updateFill(MetaObject metaObject) {
        // MyBatis Plus 3.5.12 兼容写法
        this.strictUpdateFill(metaObject, "updatedAt", LocalDateTime.class, LocalDateTime.now());
    }
}

