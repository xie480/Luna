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
 * JacksonObjectMapper ??
 */
public class JacksonObjectMapper extends ObjectMapper {

    public JacksonObjectMapper() { // 定义方法签名
        super(); // 执行语句逻辑
        this.configure(FAIL_ON_UNKNOWN_PROPERTIES, false); // 执行语句逻辑
        this.getDeserializationConfig().withoutFeatures(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES); // 执行语句逻辑
        SimpleModule simpleModule = new SimpleModule() // 执行赋值操作
                .addDeserializer(LocalDateTime.class, new LocalDateTimeDeserializer(DateTimeFormatter.ofPattern(DateTimeConstant.FORMAT_YYYYMMDDHHMM))) // 执行当前逻辑
                .addDeserializer(LocalDate.class, new LocalDateDeserializer(DateTimeFormatter.ofPattern(DateTimeConstant.FORMAT_YYYYMMDD))) // 执行当前逻辑
                .addDeserializer(LocalTime.class, new LocalTimeDeserializer(DateTimeFormatter.ofPattern(DateTimeConstant.FORMAT_HHMMSS))) // 执行当前逻辑
                .addSerializer(LocalDateTime.class, new LocalDateTimeSerializer(DateTimeFormatter.ofPattern(DateTimeConstant.FORMAT_YYYYMMDDHHMM))) // 执行当前逻辑
                .addSerializer(LocalDate.class, new LocalDateSerializer(DateTimeFormatter.ofPattern(DateTimeConstant.FORMAT_YYYYMMDD))) // 执行当前逻辑
                .addSerializer(LocalTime.class, new LocalTimeSerializer(DateTimeFormatter.ofPattern(DateTimeConstant.FORMAT_HHMMSS))); // 执行语句逻辑

        this.registerModule(simpleModule); // 执行语句逻辑
    } // 结束当前代码块
} // 结束当前代码块
