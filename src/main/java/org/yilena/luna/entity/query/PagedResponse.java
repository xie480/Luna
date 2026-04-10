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
 * 通用分页响应对象，负责把分页查询结果统一包装为前端可直接消费的结构。
 */
public class PagedResponse<T> {

    /**
     * 查询结果总条数。
     */
    private Long total;
    /**
     * 总页数。
     */
    private Long pages;
    /**
     * 当前页码。
     */
    private Long pageNo;
    /**
     * 当前页大小。
     */
    private Long pageSize;
    /**
     * 当前页记录列表。
     */
    private List<T> records;

    /**
     * 将 MyBatis Plus 分页对象转换为统一分页响应结构。
     */
    public static <T> PagedResponse<T> from(IPage<T> page) {
        return PagedResponse.<T>builder()
                .total(page.getTotal())
                .pages(page.getPages())
                .pageNo(page.getCurrent())
                .pageSize(page.getSize())
                .records(page.getRecords())
                .build();
    }
}
