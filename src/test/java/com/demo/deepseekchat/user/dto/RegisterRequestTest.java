package com.demo.deepseekchat.user.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RegisterRequestTest {

    private static Validator validator;

    @BeforeAll
    static void setUp() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            validator = factory.getValidator();
        }
    }

    private RegisterRequest aValidRequest() {
        return new RegisterRequest("testuser", "Password1!", "test@example.com", "nick",
                "captcha-id-123", "42");
    }

    @Test
    @DisplayName("所有字段合法 → 0 violations")
    void validRequest() {
        Set<ConstraintViolation<RegisterRequest>> violations = validator.validate(aValidRequest());
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("username 为空 → NotBlank violation")
    void username_blank() {
        RegisterRequest req = new RegisterRequest("", "Password1!", "test@example.com", "nick",
                "captcha-id-123", "42");
        Set<ConstraintViolation<RegisterRequest>> violations = validator.validate(req);
        assertThat(violations).anyMatch(v -> v.getMessage().contains("用户名不能为空"));
    }

    @Test
    @DisplayName("username 太短 → Size violation")
    void username_tooShort() {
        RegisterRequest req = new RegisterRequest("a", "Password1!", "test@example.com", "nick",
                "captcha-id-123", "42");
        Set<ConstraintViolation<RegisterRequest>> violations = validator.validate(req);
        assertThat(violations).anyMatch(v -> v.getMessage().contains("用户名长度"));
    }

    @Test
    @DisplayName("username 太长 → Size violation")
    void username_tooLong() {
        String longName = "x".repeat(51);
        RegisterRequest req = new RegisterRequest(longName, "Password1!", "test@example.com", "nick",
                "captcha-id-123", "42");
        Set<ConstraintViolation<RegisterRequest>> violations = validator.validate(req);
        assertThat(violations).anyMatch(v -> v.getMessage().contains("用户名长度"));
    }

    @Test
    @DisplayName("password 为空 → NotBlank violation")
    void password_blank() {
        RegisterRequest req = new RegisterRequest("testuser", "", "test@example.com", "nick",
                "captcha-id-123", "42");
        Set<ConstraintViolation<RegisterRequest>> violations = validator.validate(req);
        assertThat(violations).anyMatch(v -> v.getMessage().contains("密码不能为空"));
    }

    @Test
    @DisplayName("password 太短 → Size violation")
    void password_tooShort() {
        RegisterRequest req = new RegisterRequest("testuser", "Ab1!", "test@example.com", "nick",
                "captcha-id-123", "42");
        Set<ConstraintViolation<RegisterRequest>> violations = validator.validate(req);
        assertThat(violations).anyMatch(v -> v.getMessage().contains("密码长度"));
    }

    @Test
    @DisplayName("email 为空 → NotBlank violation")
    void email_blank() {
        RegisterRequest req = new RegisterRequest("testuser", "Password1!", "", "nick",
                "captcha-id-123", "42");
        Set<ConstraintViolation<RegisterRequest>> violations = validator.validate(req);
        assertThat(violations).anyMatch(v -> v.getMessage().contains("邮箱不能为空"));
    }

    @Test
    @DisplayName("email 格式不正确 → Email violation")
    void email_invalidFormat() {
        RegisterRequest req = new RegisterRequest("testuser", "Password1!", "not-an-email", "nick",
                "captcha-id-123", "42");
        Set<ConstraintViolation<RegisterRequest>> violations = validator.validate(req);
        assertThat(violations).anyMatch(v -> v.getMessage().contains("邮箱格式"));
    }

    @Test
    @DisplayName("email 太长 → Size violation")
    void email_tooLong() {
        String longEmail = "a".repeat(91) + "@example.com"; // >100 chars
        RegisterRequest req = new RegisterRequest("testuser", "Password1!", longEmail, "nick",
                "captcha-id-123", "42");
        Set<ConstraintViolation<RegisterRequest>> violations = validator.validate(req);
        assertThat(violations).anyMatch(v -> v.getMessage().contains("邮箱最多"));
    }

    @Test
    @DisplayName("captchaId 为空 → NotBlank violation")
    void captchaId_blank() {
        RegisterRequest req = new RegisterRequest("testuser", "Password1!", "test@example.com", "nick",
                "", "42");
        Set<ConstraintViolation<RegisterRequest>> violations = validator.validate(req);
        assertThat(violations).anyMatch(v -> v.getMessage().contains("验证码ID不能为空"));
    }

    @Test
    @DisplayName("captchaCode 为空 → NotBlank violation")
    void captchaCode_blank() {
        RegisterRequest req = new RegisterRequest("testuser", "Password1!", "test@example.com", "nick",
                "captcha-id-123", "");
        Set<ConstraintViolation<RegisterRequest>> violations = validator.validate(req);
        assertThat(violations).anyMatch(v -> v.getMessage().contains("验证码不能为空"));
    }

    @Test
    @DisplayName("nickname 为 null → 0 violations（可选字段）")
    void nickname_optional() {
        RegisterRequest req = new RegisterRequest("testuser", "Password1!", "test@example.com", null,
                "captcha-id-123", "42");
        Set<ConstraintViolation<RegisterRequest>> violations = validator.validate(req);
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("nickname 太长 → Size violation")
    void nickname_tooLong() {
        String longNick = "n".repeat(51);
        RegisterRequest req = new RegisterRequest("testuser", "Password1!", "test@example.com", longNick,
                "captcha-id-123", "42");
        Set<ConstraintViolation<RegisterRequest>> violations = validator.validate(req);
        assertThat(violations).anyMatch(v -> v.getMessage().contains("昵称最多"));
    }
}
