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

class AssignRolesRequestTest {

    private static Validator validator;

    @BeforeAll
    static void setUp() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            validator = factory.getValidator();
        }
    }

    @Test
    @DisplayName("角色列表合法 → 0 violations")
    void valid() {
        AssignRolesRequest req = new AssignRolesRequest(List.of(1L, 2L, 3L));
        Set<ConstraintViolation<AssignRolesRequest>> violations = validator.validate(req);
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("角色列表为空 → NotEmpty violation")
    void empty() {
        AssignRolesRequest req = new AssignRolesRequest(Collections.emptyList());
        Set<ConstraintViolation<AssignRolesRequest>> violations = validator.validate(req);
        assertThat(violations).anyMatch(v -> v.getMessage().contains("角色列表不能为空"));
    }

    @Test
    @DisplayName("角色列表为 null → NotEmpty violation")
    void nullList() {
        AssignRolesRequest req = new AssignRolesRequest(null);
        Set<ConstraintViolation<AssignRolesRequest>> violations = validator.validate(req);
        assertThat(violations).anyMatch(v -> v.getMessage().contains("角色列表不能为空"));
    }

    @Test
    @DisplayName("角色列表超过 20 个 → Size violation")
    void tooMany() {
        List<Long> ids = LongStream.rangeClosed(1, 21).boxed().collect(Collectors.toList());
        AssignRolesRequest req = new AssignRolesRequest(ids);
        Set<ConstraintViolation<AssignRolesRequest>> violations = validator.validate(req);
        assertThat(violations).anyMatch(v -> v.getMessage().contains("单次最多分配20个角色"));
    }
}
