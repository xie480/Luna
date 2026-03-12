package org.yilena.luna.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.apache.ibatis.reflection.MetaObject;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDateTime;

/**
 * MyBatis-Plus 配置類
 * 1. 配置分頁插件
 * 2. 配置自動填充策略（創建時間、更新時間）
 */
@Configuration
@MapperScan("org.yilena.luna.mapper")
public class MybatisPlusConfig implements MetaObjectHandler {

    /**
     * 添加分頁攔截器
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        // 添加 PostgreSQL 的分頁攔截器
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.POSTGRE_SQL));
        return interceptor;
    }

    /**
     * 插入時自動填充
     */
    @Override
    public void insertFill(MetaObject metaObject) {
        // 起始版本 3.3.0(推薦使用)
        this.strictInsertFill(metaObject, "createdAt", LocalDateTime.class, LocalDateTime.now());
        this.strictInsertFill(metaObject, "updatedAt", LocalDateTime.class, LocalDateTime.now());
    }

    /**
     * 更新時自動填充
     */
    @Override
    public void updateFill(MetaObject metaObject) {
        // 起始版本 3.3.0(推薦使用)
        this.strictUpdateFill(metaObject, "updatedAt", LocalDateTime.class, LocalDateTime.now());
    }
}
