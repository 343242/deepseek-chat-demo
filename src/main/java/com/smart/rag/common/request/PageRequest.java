package com.smart.rag.common.request;

/**
 * 通用分页请求参数
 * <p>
 * Controller 接收 @RequestParam 后通过工厂方法创建此对象，
 * 避免每个方法重复声明 page/size 参数。
 *
 * @param page 页码（从 1 开始）
 * @param size 每页大小
 */
public record PageRequest(
    int page,
    int size
) {
    public static PageRequest of(int page, int size) {
        return new PageRequest(
                Math.max(page, 1),
                Math.clamp(size, 1, 500)
        );
    }

    /**
     * 转为 MyBatis-Plus 的 Page 对象
     */
    public <T> com.baomidou.mybatisplus.extension.plugins.pagination.Page<T> toPage() {
        return new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(page, size);
    }
}
