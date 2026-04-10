package org.yilena.luna.utils;

import cn.hutool.core.lang.Snowflake;
import cn.hutool.core.util.IdUtil;
import org.springframework.stereotype.Component;

/**
 * 该工具类基于 Hutool 的雪花算法统一生成分布式唯一 ID，供业务主键和链路标识复用。
 */
@Component
public class SnowflakeIdUtil {

    /**
     * 雪花算法使用的数据中心 ID。
     */
    private static final long DATA_CENTER_ID = 1;
    /**
     * 雪花算法使用的工作节点 ID。
     */
    private static final long WORKER_ID = 1;

    /**
     * 全局复用的雪花算法实例。
     */
    private static final Snowflake snowflake = IdUtil.getSnowflake(WORKER_ID, DATA_CENTER_ID);

    /**
     * 生成下一个 long 类型唯一 ID。
     */
    public static synchronized long nextId() {
        return snowflake.nextId();
    }

    /**
     * 生成下一个字符串类型唯一 ID。
     */
    public static synchronized String nextIdStr() {
        return String.valueOf(snowflake.nextId());
    }
}
