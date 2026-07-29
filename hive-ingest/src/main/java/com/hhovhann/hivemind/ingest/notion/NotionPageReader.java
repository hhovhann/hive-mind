package com.hhovhann.hivemind.ingest.notion;

import com.hhovhann.hivemind.core.acl.AclScope;
import com.hhovhann.hivemind.core.acl.ScopeRef;
import com.hhovhann.hivemind.core.acl.Visibility;
import com.hhovhann.hivemind.core.episode.Episode;
import com.hhovhann.hivemind.core.episode.EpisodeKind;
import com.hhovhann.hivemind.core.episode.Utterance;
import com.hhovhann.hivemind.core.source.SourceRef;
import com.hhovhann.hivemind.core.source.SourceSystem;
import com.hhovhann.hivemind.core.source.SpeakerRef;
import com.hhovhann.hivemind.ingest.CorpusProperties;
import com.hhovhann.hivemind.ingest.EpisodeReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

/**
 * Reads Notion pages exported as markdown with frontmatter.
 *
 * <p>One page revision, one episode, with each block an utterance so evidence can
 * cite a paragraph rather than a whole document.
 *
 * <p>The episode is dated {@code last_edited_time}, not {@code created_time}: Notion
 * edits in place, so the text on the page is the current revision and dating it by
 * creation would file today's content under last quarter. It also means a page can
 * only ever tell you the present state — the history of how it got there lives in
 * the conversations, which is precisely why both sources are ingested.
 */
@Component
public class NotionPageReader implements EpisodeReader {

    private final CorpusProperties corpus;

    public NotionPageReader(CorpusProperties corpus) {
        this.corpus = corpus;
    }

    @Override
    public SourceSystem system() {
        return SourceSystem.NOTION;
    }

    @Override
    public List<Episode> readAll() {
        if (!Files.isDirectory(corpus.notion())) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(corpus.notion())) {
            return files.filter(path -> path.toString().endsWith(".md")).sorted().map(this::readPage).toList();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot list Notion pages in " + corpus.notion(), e);
        }
    }

    private Episode readPage(Path file) {
        String content;
        try {
            content = Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read Notion page " + file, e);
        }

        Map<String, String> frontmatter = parseFrontmatter(content);
        String body = stripFrontmatter(content);
        String pageId = frontmatter.getOrDefault("id", file.getFileName().toString());
        String author = frontmatter.getOrDefault("last_edited_by", frontmatter.getOrDefault("created_by", "unknown"));
        Instant editedAt = Instant.parse(frontmatter.getOrDefault("last_edited_time", "1970-01-01T00:00:00Z"));

        List<Utterance> utterances = new ArrayList<>();
        List<String> blocks = splitIntoBlocks(body);
        for (int ordinal = 0; ordinal < blocks.size(); ordinal++) {
            utterances.add(new Utterance(
                    ordinal,
                    new SpeakerRef(SourceSystem.NOTION, author, author),
                    editedAt,
                    blocks.get(ordinal),
                    frontmatter.get("url")));
        }

        return Episode.assemble(
                new SourceRef(SourceSystem.NOTION, pageId, frontmatter.get("url")),
                EpisodeKind.NOTION_PAGE,
                frontmatter.getOrDefault("title", pageId),
                editedAt,
                Instant.now(),
                utterances,
                AclScope.of(new ScopeRef(
                        SourceSystem.NOTION,
                        pageId,
                        Visibility.valueOf(frontmatter.getOrDefault("visibility", "PUBLIC")))));
    }

    /**
     * Parses the flat {@code key: value} subset of YAML that Notion exports use.
     *
     * <p>Deliberately not a YAML library: the frontmatter here has no nesting, no
     * anchors and no multi-line scalars, and a parser that silently accepts more
     * than the format allows hides malformed exports instead of surfacing them.
     */
    static Map<String, String> parseFrontmatter(String content) {
        Map<String, String> values = new LinkedHashMap<>();
        if (!content.startsWith("---")) {
            return values;
        }
        for (String line : content.lines().skip(1).toList()) {
            if (line.startsWith("---")) {
                break;
            }
            int colon = line.indexOf(':');
            if (colon < 0) {
                continue;
            }
            values.put(line.substring(0, colon).strip(), unquote(line.substring(colon + 1).strip()));
        }
        return values;
    }

    private static String stripFrontmatter(String content) {
        if (!content.startsWith("---")) {
            return content;
        }
        int closing = content.indexOf("\n---", 3);
        return closing < 0 ? content : content.substring(closing + 4).stripLeading();
    }

    private static String unquote(String value) {
        return value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")
                ? value.substring(1, value.length() - 1)
                : value;
    }

    /**
     * Blank-line separated blocks, with a bare heading folded into the block below it.
     *
     * <p>A lone {@code ## Decision} is a poor citation target — too short to prove
     * anything and meaningless on its own — so it travels with the text it introduces.
     */
    static List<String> splitIntoBlocks(String body) {
        List<String> blocks = new ArrayList<>();
        StringBuilder pendingHeading = new StringBuilder();
        for (String raw : body.split("\\n\\s*\\n")) {
            String block = raw.strip();
            if (block.isEmpty()) {
                continue;
            }
            if (block.startsWith("#") && !block.contains("\n")) {
                pendingHeading.append(block).append('\n');
                continue;
            }
            blocks.add(pendingHeading.isEmpty() ? block : pendingHeading + block);
            pendingHeading.setLength(0);
        }
        if (!pendingHeading.isEmpty()) {
            blocks.add(pendingHeading.toString().strip());
        }
        return blocks;
    }
}
