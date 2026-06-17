package com.smart.rag.user.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class UserUpdateRequestTest {

    private static Validator validator;

    @BeforeAll
    static void setUp() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            validator = factory.getValidator();
        }
    }

    @Test
    @DisplayName("所有字段合法 → 0 violations")
    void allFieldsValid() {
        UserUpdateRequest req = new UserUpdateRequest("nickname", "test@example.com", "13800138000", "avatar.png");
        Set<ConstraintViolation<UserUpdateRequest>> violations = validator.validate(req);
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("email 格式不正确 → Email violation")
    void email_invalid() {
        UserUpdateRequest req = new UserUpdateRequest(null, "not-an-email", null, null);
        Set<ConstraintViolation<UserUpdateRequest>> violations = validator.validate(req);
        assertThat(violations).anyMatch(v -> v.getMessage().contains("邮箱格式"));
    }

    @Test
    @DisplayName("email 太长 → Size violation")
    void email_tooLong() {
        String longEmail = "a".repeat(89) + "@example.com"; // 89 + 12 = 101 > 100
        UserUpdateRequest req = new UserUpdateRequest(null, longEmail, null, null);
        Set<ConstraintViolation<UserUpdateRequest>> violations = validator.validate(req);
        assertThat(violations).anyMatch(v -> v.getMessage().contains("邮箱过"));
    }

    @Test
    @DisplayName("nickname 太长 → Size violation")
    void nickname_tooLong() {
        String longNick = "n".repeat(51);
        UserUpdateRequest req = new UserUpdateRequest(longNick, null, null, null);
        Set<ConstraintViolation<UserUpdateRequest>> violations = validator.validate(req);
        assertThat(violations).anyMatch(v -> v.getMessage().contains("昵称最多"));
    }

    @Test
    @DisplayName("phone 格式不正确 → Pattern violation")
    void phone_invalid() {
        UserUpdateRequest req = new UserUpdateRequest(null, null, "12345", null);
        Set<ConstraintViolation<UserUpdateRequest>> violations = validator.validate(req);
        assertThat(violations).anyMatch(v -> v.getMessage().contains("手机号格式"));
    }

    @Test
    @DisplayName("phone 格式正确 → 0 violations")
    void phone_valid() {
        UserUpdateRequest req = new UserUpdateRequest(null, null, "13800138000", null);
        Set<ConstraintViolation<UserUpdateRequest>> violations = validator.validate(req);
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("所有字段为 null → 0 violations（全部可选）")
    void allNull() {
        UserUpdateRequest req = new UserUpdateRequest(null, null, null, null);
        Set<ConstraintViolation<UserUpdateRequest>> violations = validator.validate(req);
        assertThat(violations).isEmpty();
    }
}
