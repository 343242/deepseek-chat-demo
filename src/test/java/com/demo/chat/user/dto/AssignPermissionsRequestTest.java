package com.demo.chat.user.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;

class AssignPermissionsRequestTest {

    private static Validator validator;

    @BeforeAll
    static void setUp() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            validator = factory.getValidator();
        }
    }

    @Test
    @DisplayName("权限列表合法 → 0 violations")
    void valid() {
        AssignPermissionsRequest req = new AssignPermissionsRequest(List.of(1L, 2L, 3L));
        Set<ConstraintViolation<AssignPermissionsRequest>> violations = validator.validate(req);
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("权限列表为空 → NotEmpty violation")
    void empty() {
        AssignPermissionsRequest req = new AssignPermissionsRequest(Collections.emptyList());
        Set<ConstraintViolation<AssignPermissionsRequest>> violations = validator.validate(req);
        assertThat(violations).anyMatch(v -> v.getMessage().contains("权限列表不能为空"));
    }

    @Test
    @DisplayName("权限列表为 null → NotEmpty violation")
    void nullList() {
        AssignPermissionsRequest req = new AssignPermissionsRequest(null);
        Set<ConstraintViolation<AssignPermissionsRequest>> violations = validator.validate(req);
        assertThat(violations).anyMatch(v -> v.getMessage().contains("权限列表不能为空"));
    }

    @Test
    @DisplayName("权限列表超过 50 个 → Size violation")
    void tooMany() {
        List<Long> ids = LongStream.rangeClosed(1, 51).boxed().collect(Collectors.toList());
        AssignPermissionsRequest req = new AssignPermissionsRequest(ids);
        Set<ConstraintViolation<AssignPermissionsRequest>> violations = validator.validate(req);
        assertThat(violations).anyMatch(v -> v.getMessage().contains("单次最多分配50个权限"));
    }
}
