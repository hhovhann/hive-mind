package com.hhovhann.hivemind.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hhovhann.hivemind.ingest.CorpusProperties;
import com.hhovhann.hivemind.retrieval.Answer;
import com.hhovhann.hivemind.retrieval.Principal;
import com.hhovhann.hivemind.retrieval.RetrievalQuery;
import com.hhovhann.hivemind.retrieval.RetrievalService;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Runs every gold question through the real retrieval path and grades the answers.
 *
 * <p>Two signals decide whether an answer is on target, and either suffices.
 * Embedding similarity catches a correct answer worded differently from the key.
 * Key-term coverage catches the case similarity is bad at: an answer can be
 * semantically close to "the launch is June 1" while naming the wrong date, because
 * dates barely move a sentence embedding. Requiring the specific dates, names and
 * numbers from the expected answer to actually appear is what makes the temporal
 * questions gradeable at all.
 *
 * <p>Forbidden phrases are checked first and override everything. An answer that
 * leaks a restricted decision is not partially correct.
 */
@Service
public class AnswerScorer {

    private static final Logger log = LoggerFactory.getLogger(AnswerScorer.class);

    private static final double SIMILARITY_THRESHOLD = 0.62;
    private static final double TERM_COVERAGE_THRESHOLD = 0.6;

    private final CorpusProperties corpus;
    private final ObjectMapper objectMapper;
    private final EmbeddingModel embeddingModel;
    private final RetrievalService retrieval;

    public AnswerScorer(
            CorpusProperties corpus,
            ObjectMapper objectMapper,
            EmbeddingModel embeddingModel,
            RetrievalService retrieval) {
        this.corpus = corpus;
        this.objectMapper = objectMapper;
        this.embeddingModel = embeddingModel;
        this.retrieval = retrieval;
    }

    public Optional<AnswerScore> score() {
        Path file = corpus.path().resolve("ground-truth").resolve("questions.json");
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        List<GoldQuestion> questions = readQuestions(file);
        List<AnswerScore.QuestionResult> results = new ArrayList<>();
        for (GoldQuestion question : questions) {
            results.add(grade(question));
        }
        return Optional.of(new AnswerScore(results));
    }

    private AnswerScore.QuestionResult grade(GoldQuestion question) {
        RetrievalQuery query = RetrievalQuery.of(question.question())
                .as(question.principal().isEmpty()
                        ? Principal.ANONYMOUS
                        : new Principal("eval", Set.copyOf(question.principal())));
        RetrievalService.Result result = retrieval.ask(query);
        Answer answer = result.answer();
        String text = answer.text() == null ? "" : answer.text();

        String lower = text.toLowerCase(Locale.ROOT);
        String opening = openingSentence(text).toLowerCase(Locale.ROOT);
        List<String> violations = new ArrayList<>(question.mustNotSay().stream()
                .filter(phrase -> lower.contains(phrase.toLowerCase(Locale.ROOT)))
                .toList());
        question.mustNotLeadWith().stream()
                .filter(phrase -> opening.contains(phrase.toLowerCase(Locale.ROOT)))
                .forEach(phrase -> violations.add("leads with '" + phrase + "'"));

        double similarity = answer.refused() ? 0 : similarity(text, question.expected());
        AnswerScore.Verdict verdict = verdictFor(question, answer, text, violations, similarity);

        log.debug("{} -> {}", question.id(), verdict);
        return new AnswerScore.QuestionResult(
                question, answer, verdict, similarity, violations, result.pack().cards().size());
    }

    private AnswerScore.Verdict verdictFor(
            GoldQuestion question, Answer answer, String text, List<String> violations, double similarity) {
        if (!violations.isEmpty()) {
            return AnswerScore.Verdict.FORBIDDEN;
        }
        if (question.expectsNoAnswer()) {
            // The corpus holds nothing this reader may use, so declining is the answer.
            return answer.refused() ? AnswerScore.Verdict.PASS : AnswerScore.Verdict.FORBIDDEN;
        }
        if (answer.refused()) {
            return AnswerScore.Verdict.MISSED;
        }
        boolean onTarget = similarity >= SIMILARITY_THRESHOLD
                || termCoverage(text, question.expected()) >= TERM_COVERAGE_THRESHOLD;
        if (!onTarget) {
            return AnswerScore.Verdict.MISSED;
        }
        return answer.citations().isEmpty() ? AnswerScore.Verdict.UNSUPPORTED : AnswerScore.Verdict.PASS;
    }

    private double similarity(String left, String right) {
        if (left == null || left.isBlank() || right == null || right.isBlank()) {
            return 0;
        }
        var embeddings = embeddingModel
                .embedAll(List.of(TextSegment.from(left), TextSegment.from(right)))
                .content();
        return ExtractionScorer.cosine(
                embeddings.get(0).vector(), embeddings.get(1).vector());
    }

    /**
     * Share of the expected answer's distinctive terms that appear in the answer.
     *
     * <p>Dates, numbers and proper nouns only. "June 1" versus "June 15" is the entire
     * difference between a right and a wrong answer to half these questions, and it is
     * almost invisible to an embedding.
     */
    static double termCoverage(String answer, String expected) {
        Set<String> wanted = salientTerms(expected);
        if (wanted.isEmpty()) {
            return 0;
        }
        String haystack = answer.toLowerCase(Locale.ROOT);
        // Word boundaries, not substrings: "June 1" is contained in "June 15", which
        // would score the single most important wrong answer as a perfect match.
        long present = wanted.stream()
                .filter(term -> java.util.regex.Pattern.compile(
                                "\\b" + java.util.regex.Pattern.quote(term.toLowerCase(Locale.ROOT)) + "\\b")
                        .matcher(haystack)
                        .find())
                .count();
        return (double) present / wanted.size();
    }

    /** The leading claim — what a reader takes away if they read no further. */
    static String openingSentence(String text) {
        String trimmed = text.strip();
        int end = trimmed.indexOf('.');
        return end < 0 ? trimmed : trimmed.substring(0, end + 1);
    }

    static Set<String> salientTerms(String text) {
        return Arrays.stream(text.split("[^\\p{L}\\p{N}/]+"))
                .filter(token -> !token.isBlank())
                .filter(AnswerScorer::isSalient)
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
    }

    private static boolean isSalient(String token) {
        if (token.chars().anyMatch(Character::isDigit)) {
            return true;
        }
        // Proper nouns: capitalised and long enough not to be a sentence opener like "No".
        return token.length() > 3 && Character.isUpperCase(token.charAt(0));
    }

    private List<GoldQuestion> readQuestions(Path file) {
        try {
            return objectMapper.readValue(file.toFile(), GoldQuestion.Envelope.class).questions();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read gold questions " + file, e);
        }
    }
}
