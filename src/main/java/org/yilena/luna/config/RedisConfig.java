package org.yilena.luna.config; // define package

import lombok.RequiredArgsConstructor; // import dependency
import lombok.extern.slf4j.Slf4j; // import dependency
import org.springframework.context.annotation.Bean; // import dependency
import org.springframework.context.annotation.Configuration; // import dependency
import org.springframework.data.redis.connection.RedisConnectionFactory; // import dependency
import org.springframework.data.redis.core.RedisTemplate; // import dependency
import org.springframework.data.redis.serializer.StringRedisSerializer; // import dependency

/*
    redis配置类 // business logic
 */
@Configuration // declare annotation
@Slf4j // declare annotation
@RequiredArgsConstructor // declare annotation
public class RedisConfig { // define class

    @Bean // declare annotation
    public RedisTemplate<String,Object> redisTemplate(RedisConnectionFactory redisConnectionFactory){ // method definition
        log.info("<==========开始创建redis模板的对象==========>"); // assignment or init
        RedisTemplate<String,Object> redisTemplate = new RedisTemplate<>(); // assignment or init
        redisTemplate.setConnectionFactory(redisConnectionFactory); // business logic
        redisTemplate.setKeySerializer(new StringRedisSerializer()); // business logic
        return redisTemplate; // return result
    } // block end
} // block end
