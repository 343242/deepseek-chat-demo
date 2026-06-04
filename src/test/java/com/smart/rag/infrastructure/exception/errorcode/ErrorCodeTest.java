package com.smart.rag.infrastructure.exception.errorcode;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the error code enums: no code value collisions and IErrorCode interface compliance.
 */
class ErrorCodeTest {

    @Nested
    @DisplayName("IErrorCode interface compliance")
    class InterfaceCompliance {

        @Test
        @DisplayName("ClientErrorCode implements IErrorCode")
        void clientErrorCodeImplements() {
            assertThat(ClientErrorCode.BAD_REQUEST).isInstanceOf(IErrorCode.class);
        }

        @Test
        @DisplayName("ServiceErrorCode implements IErrorCode")
        void serviceErrorCodeImplements() {
            assertThat(ServiceErrorCode.NOT_FOUND).isInstanceOf(IErrorCode.class);
        }

        @Test
        @DisplayName("RemoteErrorCode implements IErrorCode")
        void remoteErrorCodeImplements() {
            assertThat(RemoteErrorCode.PROVIDER_NOT_FOUND).isInstanceOf(IErrorCode.class);
        }

        @Test
        @DisplayName("ErrorCode (legacy) implements IErrorCode")
        void legacyErrorCodeImplements() {
            assertThat(ErrorCode.SUCCESS).isInstanceOf(IErrorCode.class);
        }
    }

    @Nested
    @DisplayName("Code value ranges")
    class CodeRanges {

        @Test
        @DisplayName("ClientErrorCode codes are in range 100001-199999")
        void clientCodeRange() {
            for (ClientErrorCode code : ClientErrorCode.values()) {
                assertThat(code.getCode()).isBetween(100001, 199999);
            }
        }

        @Test
        @DisplayName("ServiceErrorCode codes are in range 200001-299999")
        void serviceCodeRange() {
            for (ServiceErrorCode code : ServiceErrorCode.values()) {
                assertThat(code.getCode()).isBetween(200001, 299999);
            }
        }

        @Test
        @DisplayName("RemoteErrorCode codes are in range 300001-399999")
        void remoteCodeRange() {
            for (RemoteErrorCode code : RemoteErrorCode.values()) {
                assertThat(code.getCode()).isBetween(300001, 399999);
            }
        }

        @Test
        @DisplayName("ErrorCode SUCCESS code is 0")
        void successCode() {
            assertThat(ErrorCode.SUCCESS.getCode()).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("No code value collisions")
    class NoCollisions {

        @Test
        @DisplayName("No duplicate code values within ClientErrorCode")
        void noDuplicatesWithinClient() {
            Set<Integer> codes = new HashSet<>();
            for (ClientErrorCode code : ClientErrorCode.values()) {
                assertThat(codes).as("Duplicate code %d in ClientErrorCode", code.getCode())
                        .doesNotContain(code.getCode());
                codes.add(code.getCode());
            }
        }

        @Test
        @DisplayName("No duplicate code values within ServiceErrorCode")
        void noDuplicatesWithinService() {
            Set<Integer> codes = new HashSet<>();
            for (ServiceErrorCode code : ServiceErrorCode.values()) {
                assertThat(codes).as("Duplicate code %d in ServiceErrorCode", code.getCode())
                        .doesNotContain(code.getCode());
                codes.add(code.getCode());
            }
        }

        @Test
        @DisplayName("No duplicate code values within RemoteErrorCode")
        void noDuplicatesWithinRemote() {
            Set<Integer> codes = new HashSet<>();
            for (RemoteErrorCode code : RemoteErrorCode.values()) {
                assertThat(codes).as("Duplicate code %d in RemoteErrorCode", code.getCode())
                        .doesNotContain(code.getCode());
                codes.add(code.getCode());
            }
        }

        @Test
        @DisplayName("No collisions across all three new enums")
        void noCollisionsAcrossEnums() {
            Set<Integer> allCodes = new HashSet<>();

            for (ClientErrorCode code : ClientErrorCode.values()) {
                assertThat(allCodes).as("Cross-enum collision on code %d", code.getCode())
                        .doesNotContain(code.getCode());
                allCodes.add(code.getCode());
            }
            for (ServiceErrorCode code : ServiceErrorCode.values()) {
                assertThat(allCodes).as("Cross-enum collision on code %d", code.getCode())
                        .doesNotContain(code.getCode());
                allCodes.add(code.getCode());
            }
            for (RemoteErrorCode code : RemoteErrorCode.values()) {
                assertThat(allCodes).as("Cross-enum collision on code %d", code.getCode())
                        .doesNotContain(code.getCode());
                allCodes.add(code.getCode());
            }
        }
    }

    @Nested
    @DisplayName("Non-null message for every enum constant")
    class NonNullMessages {

        @Test
        @DisplayName("ClientErrorCode: every constant has non-blank message")
        void clientMessages() {
            for (ClientErrorCode code : ClientErrorCode.values()) {
                assertThat(code.getMessage()).as("ClientErrorCode.%s has null/blank message", code.name())
                        .isNotNull().isNotBlank();
            }
        }

        @Test
        @DisplayName("ServiceErrorCode: every constant has non-blank message")
        void serviceMessages() {
            for (ServiceErrorCode code : ServiceErrorCode.values()) {
                assertThat(code.getMessage()).as("ServiceErrorCode.%s has null/blank message", code.name())
                        .isNotNull().isNotBlank();
            }
        }

        @Test
        @DisplayName("RemoteErrorCode: every constant has non-blank message")
        void remoteMessages() {
            for (RemoteErrorCode code : RemoteErrorCode.values()) {
                assertThat(code.getMessage()).as("RemoteErrorCode.%s has null/blank message", code.name())
                        .isNotNull().isNotBlank();
            }
        }
    }
}
