package com.smart.rag.infrastructure.llm.registry;

import com.smart.rag.infrastructure.llm.CapabilityClient;
import com.smart.rag.infrastructure.llm.LlmCapability;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link RegistrySnapshot}.
 * <p>
 * Verifies the copy-on-write snapshot semantics: immutability, filteredChains
 * pre-computation, and the disabled-set contract.
 */
class RegistrySnapshotTest {

    // ==================== construction & empty ====================

    @Nested
    @DisplayName("empty()")
    class EmptyTests {

        @Test
        @DisplayName("empty snapshot has size 0")
        void emptySizeZero() {
            RegistrySnapshot snap = RegistrySnapshot.empty();
            assertThat(snap.size()).isEqualTo(0);
        }

        @Test
        @DisplayName("empty snapshot returns empty chain for any capability")
        void emptyChainIsEmpty() {
            RegistrySnapshot snap = RegistrySnapshot.empty();
            assertThat(snap.getChain(LlmCapability.CHAT)).isEmpty();
            assertThat(snap.getChain(LlmCapability.EMBEDDING)).isEmpty();
            assertThat(snap.getChain(LlmCapability.RERANKING)).isEmpty();
        }

        @Test
        @DisplayName("empty snapshot returns null for any client lookup")
        void emptyGetClientReturnsNull() {
            RegistrySnapshot snap = RegistrySnapshot.empty();
            assertThat(snap.getClient("any")).isNull();
            assertThat(snap.getDefaultClient(LlmCapability.CHAT)).isNull();
            assertThat(snap.getDeepThinkingClient(LlmCapability.CHAT)).isNull();
        }

        @Test
        @DisplayName("empty snapshot does not report any candidate as disabled")
        void emptyDisabledSetIsClean() {
            RegistrySnapshot snap = RegistrySnapshot.empty();
            assertThat(snap.isDisabled("anything")).isFalse();
        }
    }

    // ==================== immutability ====================

    @Nested
    @DisplayName("immutability")
    class ImmutabilityTests {

        @Test
        @DisplayName("clientsById is unmodifiable")
        void clientsByIdIsUnmodifiable() {
            Map<String, CapabilityClient> original = new HashMap<>();
            original.put("c1", stubClient("c1"));

            RegistrySnapshot snap = buildSnapshot(original, Map.of(), Map.of(), Map.of(), Set.of());

            assertThatThrownBy(() -> snap.clientsById().put("c2", stubClient("c2")))
                .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("fallbackChains is unmodifiable")
        void fallbackChainsIsUnmodifiable() {
            Map<LlmCapability, List<CapabilityClient>> chains = new EnumMap<>(LlmCapability.class);
            chains.put(LlmCapability.CHAT, List.of(stubClient("c1")));

            RegistrySnapshot snap = buildSnapshot(Map.of(), chains, Map.of(), Map.of(), Set.of());

            assertThatThrownBy(() -> snap.fallbackChains().put(LlmCapability.EMBEDDING, List.of()))
                .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("disabledSet is unmodifiable")
        void disabledSetIsUnmodifiable() {
            RegistrySnapshot snap = buildSnapshot(
                Map.of(), Map.of(), Map.of(), Map.of(), Set.of("c1"));

            assertThatThrownBy(() -> snap.disabledSet().add("c2"))
                .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("snapshot is a read-only view — relies on AtomicReference + caller discipline (not defensive copy)")
        void snapshotIsViewBackedBySource() {
            // RegistrySnapshot uses Collections.unmodifiableMap (a view), NOT defensive copy.
            // The contract is: callers pass freshly-built maps and never mutate them afterwards.
            // AtomicReference<CAS> provides the write-isolation guarantee, not deep cloning.
            // This test documents that contract — the snapshot is stable ONLY because
            // LlmClientRegistry builds a new map for each refresh.
            Map<String, CapabilityClient> original = new HashMap<>();
            original.put("c1", stubClient("c1"));

            RegistrySnapshot snap = buildSnapshot(original, Map.of(), Map.of(), Map.of(), Set.of());

            // The snapshot reflects the source map's contents at access time.
            // The unmodifiable wrapper prevents add/remove via the snapshot reference,
            // but does NOT freeze the underlying data.
            assertThat(snap.size()).isEqualTo(1);
            assertThat(snap.clientsById()).containsKey("c1");
        }
    }

    // ==================== filteredChains pre-computation ====================

    @Nested
    @DisplayName("filteredChains (pre-computed, disabled excluded)")
    class FilteredChainsTests {

        @Test
        @DisplayName("disabled candidates are excluded from filteredChains")
        void disabledCandidatesExcluded() {
            CapabilityClient c1 = stubClient("c1");
            CapabilityClient c2 = stubClient("c2");
            CapabilityClient c3 = stubClient("c3");

            Map<LlmCapability, List<CapabilityClient>> chains = new EnumMap<>(LlmCapability.class);
            chains.put(LlmCapability.CHAT, List.of(c1, c2, c3));

            RegistrySnapshot snap = buildSnapshot(
                Map.of("c1", c1, "c2", c2, "c3", c3),
                chains,
                Map.of(),
                Map.of(),
                Set.of("c2"));  // c2 disabled

            List<CapabilityClient> filtered = snap.getChain(LlmCapability.CHAT);
            assertThat(filtered).hasSize(2);
            assertThat(filtered).extracting(CapabilityClient::candidateId)
                .containsExactly("c1", "c3");
        }

        @Test
        @DisplayName("no disabled candidates → filteredChains equals original chain")
        void noDisabledCandidatesKeepsChain() {
            CapabilityClient c1 = stubClient("c1");
            CapabilityClient c2 = stubClient("c2");

            Map<LlmCapability, List<CapabilityClient>> chains = new EnumMap<>(LlmCapability.class);
            chains.put(LlmCapability.EMBEDDING, List.of(c1, c2));

            RegistrySnapshot snap = buildSnapshot(
                Map.of("c1", c1, "c2", c2),
                chains,
                Map.of(), Map.of(),
                Set.of());

            assertThat(snap.getChain(LlmCapability.EMBEDDING))
                .extracting(CapabilityClient::candidateId)
                .containsExactly("c1", "c2");
        }

        @Test
        @DisplayName("all candidates disabled → empty chain")
        void allDisabledEmptyChain() {
            CapabilityClient c1 = stubClient("c1");
            CapabilityClient c2 = stubClient("c2");

            Map<LlmCapability, List<CapabilityClient>> chains = new EnumMap<>(LlmCapability.class);
            chains.put(LlmCapability.CHAT, List.of(c1, c2));

            RegistrySnapshot snap = buildSnapshot(
                Map.of("c1", c1, "c2", c2),
                chains,
                Map.of(), Map.of(),
                Set.of("c1", "c2"));

            assertThat(snap.getChain(LlmCapability.CHAT)).isEmpty();
        }

        @Test
        @DisplayName("capability with no chain registered returns empty list")
        void noChainReturnsEmpty() {
            RegistrySnapshot snap = RegistrySnapshot.empty();
            assertThat(snap.getChain(LlmCapability.RERANKING)).isEmpty();
        }
    }

    // ==================== getClient / isDisabled ====================

    @Nested
    @DisplayName("getClient / isDisabled")
    class GetClientTests {

        @Test
        @DisplayName("getClient returns client for active candidateId")
        void getClientActive() {
            CapabilityClient c1 = stubClient("c1");
            RegistrySnapshot snap = buildSnapshot(
                Map.of("c1", c1), Map.of(), Map.of(), Map.of(), Set.of());

            assertThat(snap.getClient("c1")).isSameAs(c1);
        }

        @Test
        @DisplayName("getClient returns null for disabled candidateId (even if client exists)")
        void getClientDisabledReturnsNull() {
            CapabilityClient c1 = stubClient("c1");
            RegistrySnapshot snap = buildSnapshot(
                Map.of("c1", c1), Map.of(), Map.of(), Map.of(), Set.of("c1"));

            assertThat(snap.getClient("c1")).isNull();
        }

        @Test
        @DisplayName("getClient returns null for unknown candidateId")
        void getClientUnknownReturnsNull() {
            RegistrySnapshot snap = RegistrySnapshot.empty();
            assertThat(snap.getClient("nonexistent")).isNull();
        }

        @Test
        @DisplayName("isDisabled reflects disabled set")
        void isDisabledReflects() {
            RegistrySnapshot snap = buildSnapshot(
                Map.of(), Map.of(), Map.of(), Map.of(), Set.of("c1"));

            assertThat(snap.isDisabled("c1")).isTrue();
            assertThat(snap.isDisabled("c2")).isFalse();
        }
    }

    // ==================== getDefaultClient / getDeepThinkingClient ====================

    @Nested
    @DisplayName("getDefaultClient / getDeepThinkingClient")
    class DefaultClientTests {

        @Test
        @DisplayName("getDefaultClient returns mapped client")
        void getDefaultClientReturnsClient() {
            CapabilityClient c1 = stubClient("c1");
            RegistrySnapshot snap = buildSnapshot(
                Map.of("c1", c1),
                Map.of(),
                Map.of(LlmCapability.CHAT, "c1"),
                Map.of(),
                Set.of());

            assertThat(snap.getDefaultClient(LlmCapability.CHAT)).isSameAs(c1);
        }

        @Test
        @DisplayName("getDefaultClient returns null when default candidate is disabled")
        void getDefaultClientDisabledReturnsNull() {
            CapabilityClient c1 = stubClient("c1");
            RegistrySnapshot snap = buildSnapshot(
                Map.of("c1", c1),
                Map.of(),
                Map.of(LlmCapability.CHAT, "c1"),
                Map.of(),
                Set.of("c1"));

            assertThat(snap.getDefaultClient(LlmCapability.CHAT)).isNull();
        }

        @Test
        @DisplayName("getDefaultClient returns null when no default configured")
        void getDefaultClientNotConfiguredReturnsNull() {
            RegistrySnapshot snap = RegistrySnapshot.empty();
            assertThat(snap.getDefaultClient(LlmCapability.CHAT)).isNull();
        }

        @Test
        @DisplayName("getDeepThinkingClient returns mapped client")
        void getDeepThinkingClientReturnsClient() {
            CapabilityClient c1 = stubClient("c1");
            RegistrySnapshot snap = buildSnapshot(
                Map.of("c1", c1),
                Map.of(),
                Map.of(),
                Map.of(LlmCapability.CHAT, "c1"),
                Set.of());

            assertThat(snap.getDeepThinkingClient(LlmCapability.CHAT)).isSameAs(c1);
        }

        @Test
        @DisplayName("getDeepThinkingClient returns null when deepThinking candidate is disabled")
        void getDeepThinkingClientDisabledReturnsNull() {
            CapabilityClient c1 = stubClient("c1");
            RegistrySnapshot snap = buildSnapshot(
                Map.of("c1", c1),
                Map.of(),
                Map.of(),
                Map.of(LlmCapability.CHAT, "c1"),
                Set.of("c1"));

            assertThat(snap.getDeepThinkingClient(LlmCapability.CHAT)).isNull();
        }
    }

    // ==================== size ====================

    @Test
    @DisplayName("size returns count of registered clients (ignoring disabled)")
    void sizeReturnsRegisteredCount() {
        RegistrySnapshot snap = buildSnapshot(
            Map.of("c1", stubClient("c1"), "c2", stubClient("c2"), "c3", stubClient("c3")),
            Map.of(), Map.of(), Map.of(),
            Set.of("c2"));  // disabled

        // size counts all registered, not filtered
        assertThat(snap.size()).isEqualTo(3);
    }

    // ==================== helpers ====================

    private static RegistrySnapshot buildSnapshot(
            Map<String, CapabilityClient> clientsById,
            Map<LlmCapability, List<CapabilityClient>> fallbackChains,
            Map<LlmCapability, String> defaultClients,
            Map<LlmCapability, String> deepThinkingClients,
            Set<String> disabledSet) {
        return new RegistrySnapshot(clientsById, fallbackChains, defaultClients,
            deepThinkingClients, Map.of(), disabledSet);
    }

    private static CapabilityClient stubClient(String id) {
        return new CapabilityClient() {
            @Override public String candidateId() { return id; }
            @Override public String providerId() { return "test"; }
            @Override public String modelName() { return "test-model"; }
            @Override public LlmCapability capability() { return LlmCapability.CHAT; }
            @Override public boolean isAvailable() { return true; }
        };
    }
}
