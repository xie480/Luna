package org.yilena.luna.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 知識庫來源類型枚舉
 */
@Getter
@AllArgsConstructor
public enum SourceType {
    FILE("FILE", "本地文件"),
    WEB_SEARCH("WEB_SEARCH", "网络搜索"),
    MANUAL_INPUT("MANUAL_INPUT", "手动输入");

    @EnumValue
    private final String code;
    private final String desc;
}
