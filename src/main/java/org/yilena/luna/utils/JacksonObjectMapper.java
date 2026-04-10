package org.yilena.luna.utils;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateDeserializer;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateSerializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalTimeSerializer;
import org.yilena.luna.constants.DateTimeConstant;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

import static com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES;

/**
 * 该 ObjectMapper 扩展类用于统一项目中的 JSON 序列化规则，重点处理未知字段兼容和日期时间格式。
 */
public class JacksonObjectMapper extends ObjectMapper {

    public JacksonObjectMapper() {
        super();
        /**
         * 先关闭未知字段报错，避免前后端字段演进时出现反序列化失败。
         */
        this.configure(FAIL_ON_UNKNOWN_PROPERTIES, false);
        this.getDeserializationConfig().withoutFeatures(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        /**
         * 再统一注册日期时间序列化与反序列化格式，保证全局 JSON 时间字段表现一致。
         */
        SimpleModule simpleModule = new SimpleModule()
                .addDeserializer(LocalDateTime.class, new LocalDateTimeDeserializer(DateTimeFormatter.ofPattern(DateTimeConstant.FORMAT_YYYYMMDDHHMM)))
                .addDeserializer(LocalDate.class, new LocalDateDeserializer(DateTimeFormatter.ofPattern(DateTimeConstant.FORMAT_YYYYMMDD)))
                .addDeserializer(LocalTime.class, new LocalTimeDeserializer(DateTimeFormatter.ofPattern(DateTimeConstant.FORMAT_HHMMSS)))
                .addSerializer(LocalDateTime.class, new LocalDateTimeSerializer(DateTimeFormatter.ofPattern(DateTimeConstant.FORMAT_YYYYMMDDHHMM)))
                .addSerializer(LocalDate.class, new LocalDateSerializer(DateTimeFormatter.ofPattern(DateTimeConstant.FORMAT_YYYYMMDD)))
                .addSerializer(LocalTime.class, new LocalTimeSerializer(DateTimeFormatter.ofPattern(DateTimeConstant.FORMAT_HHMMSS)));

        /**
         * 最后注册自定义模块，使全局 ObjectMapper 生效统一配置。
         */
        this.registerModule(simpleModule);
    }
}
