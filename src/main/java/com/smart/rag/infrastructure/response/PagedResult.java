package com.smart.rag.infrastructure.response;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 通用分页结果封装
 * <p>
 * 替代手动拼接 Map.of("content", ..., "page", ..., "total", ...)。
 * 所有分页接口统一返回此类型。
 *
 * @param content    当前页数据
 * @param page       当前页码（从 1 开始）
 * @param size       每页大小
 * @param total      总记录数
 * @param totalPages 总页数
 */
public record PagedResult<T>(
    List<T> content,
    int page,
    int size,
    long total,
    int totalPages
) {
    /**
     * 从 MyBatis-Plus Page 直接转换（实体类型不变）
     */
    public static <T> PagedResult<T> of(Page<T> page) {
        return new PagedResult<>(
                page.getRecords(),
                (int) page.getCurrent(),
                (int) page.getSize(),
                page.getTotal(),
                (int) page.getPages()
        );
    }

    /**
     * 从 MyBatis-Plus Page 转换（实体 → DTO 映射）
     * <p>
     * 避免 Service 层手动 stream().map() 再拼 Map。
     *
     * @param page      MyBatis-Plus 分页结果
     * @param converter 实体到 DTO 的转换函数
     */
    public static <E, T> PagedResult<T> of(Page<E> page, Function<E, T> converter) {
        List<T> content = page.getRecords().stream()
                .map(converter)
                .collect(Collectors.toList());
        return new PagedResult<>(
                content,
                (int) page.getCurrent(),
                (int) page.getSize(),
                page.getTotal(),
                (int) page.getPages()
        );
    }
}
