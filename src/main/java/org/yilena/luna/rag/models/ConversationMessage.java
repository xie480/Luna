package org.yilena.luna.rag.models;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class ConversationMessage {
    String role;
    String content;
}
