package org.yilena.luna.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 知識庫來源類型枚舉
 */
@Getter
@AllArgsConstructor
public enum SourceType {
    FILE(0, "FILE", "本地文件"),
    WEB_SEARCH(1, "WEB_SEARCH", "网络搜索"),
    MANUAL_INPUT(2, "MANUAL_INPUT", "手动输入");

    @EnumValue
    private final Integer code;

    @JsonValue
    private final String value;

    private final String desc;
}
