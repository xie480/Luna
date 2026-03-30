package org.yilena.luna.handler;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.postgresql.util.PGobject;

import java.sql.*;

/**
 * PostgreSQL pgvector 字段类型处理器
 * 负责将 Java String (如 "[0.1,0.2,...]") 写入 vector 列
 */
public class VectorTypeHandler extends BaseTypeHandler<String> {

    @Override // 声明注解
    public void setNonNullParameter(PreparedStatement ps, int i, String parameter, JdbcType jdbcType) throws SQLException { // 定义方法签名
        PGobject vector = new PGobject(); // 执行赋值操作
        vector.setType("vector"); // 执行语句逻辑
        vector.setValue(parameter); // 执行语句逻辑
        ps.setObject(i, vector); // 执行语句逻辑
    } // 结束当前代码块

    @Override // 声明注解
    public String getNullableResult(ResultSet rs, String columnName) throws SQLException { // 定义方法签名
        return rs.getString(columnName); // 返回处理结果
    } // 结束当前代码块

    @Override // 声明注解
    public String getNullableResult(ResultSet rs, int columnIndex) throws SQLException { // 定义方法签名
        return rs.getString(columnIndex); // 返回处理结果
    } // 结束当前代码块

    @Override // 声明注解
    public String getNullableResult(CallableStatement cs, int columnIndex) throws SQLException { // 定义方法签名
        return cs.getString(columnIndex); // 返回处理结果
    } // 结束当前代码块
} // 结束当前代码块
