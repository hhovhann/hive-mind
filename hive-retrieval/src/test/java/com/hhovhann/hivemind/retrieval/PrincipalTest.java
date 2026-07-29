package com.hhovhann.hivemind.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PrincipalTest {

    @Test
    @DisplayName("a grant list parses the same however it was spaced")
    void parsesGrantList() {
        Principal principal = Principal.parse("cli", " slack:C_EXEC ,zoom:M_EXEC_OFFSITE, ");

        assertThat(principal.grants()).containsExactlyInAnyOrder("slack:C_EXEC", "zoom:M_EXEC_OFFSITE");
        assertThat(principal.id()).isEqualTo("cli");
    }

    @Test
    @DisplayName("absent, empty and whitespace all mean no access rather than all access")
    void defaultsToAnonymous() {
        assertThat(Principal.parse("cli", null)).isEqualTo(Principal.ANONYMOUS);
        assertThat(Principal.parse("cli", "")).isEqualTo(Principal.ANONYMOUS);
        assertThat(Principal.parse("cli", "  ,  ,")).isEqualTo(Principal.ANONYMOUS);
    }

    @Test
    @DisplayName("holding some of what a fact requires is not holding it")
    void readingRequiresEveryGrant() {
        Principal principal = Principal.parse("cli", "slack:C_EXEC");

        // A fact drawn half from a public channel and half from a private one inherits
        // both requirements, and the union is the intersection of who may read it.
        assertThat(principal.mayRead(java.util.Set.of("slack:C_EXEC"))).isTrue();
        assertThat(principal.mayRead(java.util.Set.of("slack:C_EXEC", "notion:P_BOARD_Q1")))
                .isFalse();
        assertThat(principal.mayRead(java.util.Set.of())).isTrue();
    }
}