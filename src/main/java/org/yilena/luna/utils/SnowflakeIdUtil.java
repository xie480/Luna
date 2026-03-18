package org.yilena.luna.utils;

import cn.hutool.core.lang.Snowflake;
import cn.hutool.core.util.IdUtil;
import org.springframework.stereotype.Component;

/**
 * 雪花算法 ID 生成工具類
 * 封裝 Hutool 的 Snowflake 算法
 */
@Component
public class SnowflakeIdUtil {

    // 數據中心 ID (0-31)
    private static final long DATA_CENTER_ID = 1;
    // 機器 ID (0-31)
    private static final long WORKER_ID = 1;

    private static final Snowflake snowflake = IdUtil.getSnowflake(WORKER_ID, DATA_CENTER_ID);

    /**
     * 生成下一個 ID
     * @return Long 類型的雪花 ID
     */
    public static synchronized long nextId() {
        return snowflake.nextId();
    }

    /**
     * 生成下一個 ID (String 類型)
     * @return String 類型的雪花 ID
     */
    public static synchronized String nextIdStr() {
        return String.valueOf(snowflake.nextId());
    }
}
