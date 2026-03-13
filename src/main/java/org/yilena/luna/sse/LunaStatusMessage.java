package org.yilena.luna.sse;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class LunaStatusMessage {
    /**
     * 状态码，如 THINKING, SEARCHING, FIXING, IDLE
     */
    private String status;
    
    /**
     * 展示给用户的文本，如 "Luna 正在全网搜索最新资讯..."
     */
    private String message;
    
    /**
     * 时间戳
     */
    private long timestamp;
}
