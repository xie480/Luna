package org.yilena.luna.entity.query;

import com.baomidou.mybatisplus.core.metadata.IPage;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
/**
 * PagedResponse ??
 */
public class PagedResponse<T> {

    private Long total; // 声明成员字段
    private Long pages; // 声明成员字段
    private Long pageNo; // 声明成员字段
    private Long pageSize; // 声明成员字段
    private List<T> records; // 声明成员字段

    public static <T> PagedResponse<T> from(IPage<T> page) { // 定义方法签名
        return PagedResponse.<T>builder() // 返回处理结果
                .total(page.getTotal()) // 执行当前逻辑
                .pages(page.getPages()) // 执行当前逻辑
                .pageNo(page.getCurrent()) // 执行当前逻辑
                .pageSize(page.getSize()) // 执行当前逻辑
                .records(page.getRecords()) // 执行当前逻辑
                .build(); // 执行语句逻辑
    } // 结束当前代码块
} // 结束当前代码块
