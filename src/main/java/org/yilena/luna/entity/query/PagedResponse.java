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

    private Long total;
    private Long pages;
    private Long pageNo;
    private Long pageSize;
    private List<T> records;

    public static <T> PagedResponse<T> from(IPage<T> page) {
        // 将 MyBatis Plus 分页对象转换为前端统一分页结构。
        return PagedResponse.<T>builder()
                .total(page.getTotal())
                .pages(page.getPages())
                .pageNo(page.getCurrent())
                .pageSize(page.getSize())
                .records(page.getRecords())
                .build();
    }
}
