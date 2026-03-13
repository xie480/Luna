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
    FILE(0, "本地文件"),
    WEB_SEARCH(1, "网络搜索"),
    MANUAL_INPUT(2, "手动输入");

    @EnumValue
    @JsonValue
    private final Integer code;
    private final String desc;
}
