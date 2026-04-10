package org.yilena.luna.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * 该配置类负责初始化项目使用的 RedisTemplate，统一 Redis 连接工厂和序列化策略。
 */
@Configuration
@Slf4j
@RequiredArgsConstructor
public class RedisConfig {

    /**
     * 创建 RedisTemplate，统一使用字符串序列化键，便于缓存键在排障和运维场景下直接查看。
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory redisConnectionFactory) {
        log.info("<==========开始创建 RedisTemplate 对象=========>");
        RedisTemplate<String, Object> redisTemplate = new RedisTemplate<>();
        redisTemplate.setConnectionFactory(redisConnectionFactory);
        redisTemplate.setKeySerializer(new StringRedisSerializer());
        return redisTemplate;
    }
}
