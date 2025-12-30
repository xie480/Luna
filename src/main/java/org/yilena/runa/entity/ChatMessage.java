package org.yilena.runa.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class ChatMessage {
    public enum Role { USER, ASSISTANT, SYSTEM }
    private Role role;
    private String content;
}
