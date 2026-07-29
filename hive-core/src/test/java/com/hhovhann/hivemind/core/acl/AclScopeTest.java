package com.hhovhann.hivemind.core.acl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hhovhann.hivemind.core.source.SourceSystem;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AclScopeTest {

    private static final ScopeRef GENERAL = ScopeRef.publicScope(SourceSystem.SLACK, "C_GENERAL");
    private static final ScopeRef EXEC = ScopeRef.restricted(SourceSystem.SLACK, "C_EXEC");
    private static final ScopeRef ROADMAP_PAGE = ScopeRef.restricted(SourceSystem.NOTION, "P_ROADMAP");

    @Test
    @DisplayName("public containers need no grant")
    void publicScopeNeedsNoGrant() {
        AclScope scope = AclScope.of(GENERAL);

        assertThat(scope.requiredGrants()).isEmpty();
        assertThat(scope.readableBy(Set.of())).isTrue();
        assertThat(scope.effectiveVisibility()).isEqualTo(Visibility.PUBLIC);
    }

    @Test
    @DisplayName("a fact derived from public and private sources requires the private grant")
    void derivedFactInheritsTheTighterSource() {
        AclScope derived = AclScope.inheritedFrom(List.of(AclScope.of(GENERAL), AclScope.of(EXEC)));

        assertThat(derived.requiredGrants()).containsExactly("slack:C_EXEC");
        assertThat(derived.effectiveVisibility()).isEqualTo(Visibility.RESTRICTED);
        assertThat(derived.readableBy(Set.of())).isFalse();
        assertThat(derived.readableBy(Set.of("slack:C_EXEC"))).isTrue();
    }

    @Test
    @DisplayName("grants are required cumulatively across systems — holding one is not enough")
    void requirementsAccumulateAcrossSystems() {
        AclScope derived = AclScope.inheritedFrom(List.of(AclScope.of(EXEC), AclScope.of(ROADMAP_PAGE)));

        assertThat(derived.requiredGrants()).containsExactlyInAnyOrder("slack:C_EXEC", "notion:P_ROADMAP");
        assertThat(derived.readableBy(Set.of("slack:C_EXEC"))).isFalse();
        assertThat(derived.readableBy(Set.of("slack:C_EXEC", "notion:P_ROADMAP"))).isTrue();
    }

    @Test
    @DisplayName("deriving from zero sources fails closed rather than defaulting to workspace-readable")
    void derivingFromNothingIsRejected() {
        assertThatThrownBy(() -> AclScope.inheritedFrom(List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fail open");
    }

    @Test
    @DisplayName("grant order is deterministic so query parameters and hashes are stable")
    void grantOrderIsStable() {
        AclScope one = AclScope.of(ROADMAP_PAGE, EXEC);
        AclScope other = AclScope.of(EXEC, ROADMAP_PAGE);

        assertThat(one).isEqualTo(other);
        assertThat(one.requiredGrants()).containsExactlyElementsOf(other.requiredGrants());
    }
}
