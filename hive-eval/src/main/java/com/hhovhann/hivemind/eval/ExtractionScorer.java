package com.hhovhann.hivemind.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hhovhann.hivemind.core.ontology.ExtractedFact;
import com.hhovhann.hivemind.core.ontology.ValidationIssue;
import com.hhovhann.hivemind.extract.ExtractionResult;
import com.hhovhann.hivemind.ingest.CorpusProperties;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.springframework.stereotype.Service;

/**
 * Scores an extraction run against the corpus answer key.
 *
 * <p>Matching is by embedding similarity, not word overlap. The first version used
 * token Jaccard and scored "The newsletter will continue to be published" as a miss
 * against "The newsletter is retained; the question is closed until 2027 planning" —
 * the same fact, sharing one content word. A benchmark that punishes correct
 * paraphrase measures vocabulary, not extraction, and would have sent tuning effort
 * at a problem that did not exist.
 *
 * <p>Two recall numbers are reported and the gap between them is the point:
 *
 * <ul>
 *   <li><b>strict</b> — found, and labelled with the right type
 *   <li><b>content</b> — found at all, whatever it was labelled
 * </ul>
 *
 * A large gap means the extractor is reading the conversation correctly and
 * classifying it badly, which is a prompt problem. A small gap with low content
 * recall means it is not finding the information at all, which is a different
 * problem entirely. Collapsing both into one number hides which one you have.
 *
 * <p>No model judges the model. An LLM-as-judge is slower, costs money per run, and
 * disagrees with itself between runs — ruinous when the whole purpose of the number
 * is to compare two prompt versions.
 */
@Service
public class ExtractionScorer {

    /**
     * Cosine similarity above which two statements are the same fact.
     *
     * <p>Calibrated against this corpus: correct paraphrases land around 0.75–0.95,
     * while different facts about the same subject ("Frontier premieres June 1" vs
     * "Frontier numbers beat the model") sit below 0.7.
     */
    private static final double MATCH_THRESHOLD = 0.72;

    private final CorpusProperties corpus;
    private final ObjectMapper objectMapper;
    private final EmbeddingModel embeddingModel;

    public ExtractionScorer(CorpusProperties corpus, ObjectMapper objectMapper, EmbeddingModel embeddingModel) {
        this.corpus = corpus;
        this.objectMapper = objectMapper;
        this.embeddingModel = embeddingModel;
    }

    /** @return empty when the corpus ships no answer key */
    public Optional<ExtractionScore> score(List<ExtractionResult> results) {
        Path goldFile = corpus.path().resolve("ground-truth").resolve("facts.json");
        if (!Files.exists(goldFile)) {
            return Optional.empty();
        }
        List<GoldFact> gold = readGold(goldFile);

        Map<String, List<ExtractedFact>> byEpisode = new HashMap<>();
        int proposed = 0;
        int groundingRejections = 0;
        for (ExtractionResult result : results) {
            byEpisode.computeIfAbsent(result.episodeId(), key -> new ArrayList<>()).addAll(result.accepted());
            proposed += result.proposed();
            groundingRejections += (int) result.rejected().stream()
                    .flatMap(rejected -> rejected.issues().stream())
                    .filter(ValidationIssue::isRejection)
                    .filter(issue -> issue.field().startsWith("evidence"))
                    .count();
        }

        Map<String, float[]> vectors = embedAll(gold, byEpisode);

        List<ExtractionScore.Match> matches = new ArrayList<>();
        List<ExtractionScore.Match> mistyped = new ArrayList<>();
        List<GoldFact> missed = new ArrayList<>();
        Map<String, int[]> perType = new TreeMap<>();

        for (GoldFact goldFact : gold) {
            perType.computeIfAbsent(goldFact.type(), key -> new int[2])[0]++;
            Optional<ExtractionScore.Match> best = bestMatch(goldFact, byEpisode, vectors);
            if (best.isEmpty()) {
                missed.add(goldFact);
            } else if (best.get().found().type().name().equals(goldFact.type())) {
                matches.add(best.get());
                perType.get(goldFact.type())[1]++;
            } else {
                mistyped.add(best.get());
            }
        }

        Map<String, ExtractionScore.TypeRecall> byType = new LinkedHashMap<>();
        perType.forEach((type, counts) -> byType.put(type, new ExtractionScore.TypeRecall(counts[0], counts[1])));

        return Optional.of(new ExtractionScore(
                gold.size(), matches.size(), matches, mistyped, missed, byType, proposed, groundingRejections));
    }

    /**
     * Best candidate for a gold fact, ignoring type.
     *
     * <p>Type is compared by the caller so a right-content wrong-label result can be
     * told apart from nothing at all. Candidates are restricted to the episodes the
     * key names: a fact about the June date extracted from the wrong conversation is
     * not evidence that the right conversation was understood.
     */
    private Optional<ExtractionScore.Match> bestMatch(
            GoldFact gold, Map<String, List<ExtractedFact>> byEpisode, Map<String, float[]> vectors) {
        float[] goldVector = vectors.get(gold.statement());
        if (goldVector == null) {
            return Optional.empty();
        }
        ExtractionScore.Match best = null;
        for (String episodeId : gold.sources()) {
            for (ExtractedFact candidate : byEpisode.getOrDefault(episodeId, List.of())) {
                float[] candidateVector = vectors.get(candidate.statement());
                if (candidateVector == null) {
                    continue;
                }
                double similarity = cosine(goldVector, candidateVector);
                if (similarity >= MATCH_THRESHOLD && (best == null || similarity > best.similarity())) {
                    best = new ExtractionScore.Match(gold, candidate, episodeId, similarity);
                }
            }
        }
        return Optional.ofNullable(best);
    }

    /** One batched call for every distinct statement — gold and extracted alike. */
    private Map<String, float[]> embedAll(List<GoldFact> gold, Map<String, List<ExtractedFact>> byEpisode) {
        List<String> statements = new ArrayList<>();
        gold.forEach(fact -> statements.add(fact.statement()));
        byEpisode.values().forEach(facts -> facts.forEach(fact -> {
            if (fact.statement() != null) {
                statements.add(fact.statement());
            }
        }));
        List<String> distinct = statements.stream().distinct().toList();

        List<Embedding> embeddings =
                embeddingModel.embedAll(distinct.stream().map(TextSegment::from).toList()).content();

        Map<String, float[]> vectors = new HashMap<>();
        for (int i = 0; i < distinct.size(); i++) {
            vectors.put(distinct.get(i), embeddings.get(i).vector());
        }
        return vectors;
    }

    static double cosine(float[] left, float[] right) {
        double dot = 0;
        double leftNorm = 0;
        double rightNorm = 0;
        for (int i = 0; i < Math.min(left.length, right.length); i++) {
            dot += left[i] * right[i];
            leftNorm += left[i] * left[i];
            rightNorm += right[i] * right[i];
        }
        return leftNorm == 0 || rightNorm == 0 ? 0 : dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }

    private List<GoldFact> readGold(Path goldFile) {
        try {
            return objectMapper.readValue(goldFile.toFile(), GoldFact.Envelope.class).facts();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read gold set " + goldFile, e);
        }
    }
}
