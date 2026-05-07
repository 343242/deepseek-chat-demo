package com.demo.deepseekchat.security.dto;

/**
 * 滑块拼图验证码结果。
 *
 * @param captchaId     验证码唯一 ID
 * @param backgroundImage 底图（带凹槽遮罩），PNG Base64
 * @param puzzleImage   拼图块图片，PNG Base64
 * @param answer        正确 x 坐标（仅 dev profile 返回，其他环境为 null）
 */
public record CaptchaResult(
    String captchaId,
    String backgroundImage,
    String puzzleImage,
    Integer answer
) {}
