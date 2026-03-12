package org.yilena.luna.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalTime;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class ChatMessage {
    public enum Role {
        // 用户
        USER,
        // Luna
        LUNA,
        // 上下文压缩
        CONTEXT_SUMMARY,
        // 开机命令
        STARTUP,
        // 关机命令
        SHUTDOWN
    }
    private Role role;
    private String content;
    private LocalTime time;
}
