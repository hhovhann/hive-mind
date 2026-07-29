package com.hhovhann.hivemind.eval;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AnswerScorerTest {

    @Test
    @DisplayName("the opening sentence is what a skim-reader takes away")
    void openingSentenceIsTheLeadingClaim() {
        assertThat(AnswerScorer.openingSentence("June 1, 2026 [5]. It was previously May 4."))
                .isEqualTo("June 1, 2026 [5].");
        assertThat(AnswerScorer.openingSentence("No full stop here")).isEqualTo("No full stop here");
    }

    @Test
    @DisplayName("term coverage catches a wrong date that an embedding would call close enough")
    void termCoverageDistinguishesDates() {
        String expected = "June 1, 2026.";

        assertThat(AnswerScorer.termCoverage("The launch is on June 1, 2026.", expected)).isEqualTo(1.0);
        assertThat(AnswerScorer.termCoverage("The launch is on June 15, 2026.", expected))
                .isLessThan(1.0);
    }

    @Test
    @DisplayName("only dates, numbers and proper nouns count — filler words would always match")
    void salientTermsAreDistinctiveOnly() {
        assertThat(AnswerScorer.salientTerms("Alex Chen owns it, targeting the end of Q2 2026"))
                .contains("Alex", "Chen", "2026")
                .doesNotContain("owns", "the", "end");
    }
}
