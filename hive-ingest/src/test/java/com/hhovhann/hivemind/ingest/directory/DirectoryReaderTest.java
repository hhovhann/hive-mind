package com.hhovhann.hivemind.ingest.directory;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hhovhann.hivemind.core.entity.Person;
import com.hhovhann.hivemind.core.source.SourceSystem;
import com.hhovhann.hivemind.ingest.CorpusProperties;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DirectoryReaderTest {

    private static final CorpusProperties CORPUS =
            new CorpusProperties(Path.of(System.getProperty("hive.repo.root"), "corpus", "meridian-media"));

    private final Directory directory = new DirectoryReader(CORPUS, new ObjectMapper()).read();

    @Test
    @DisplayName("the eight employees plus the external partner are all found")
    void findsEveryone() {
        assertThat(directory.people()).hasSize(9);
        assertThat(directory.people())
                .extracting(Person::canonicalName)
                .contains("Alexandra Petrova", "Alex Chen", "Ingrid Halvorsen");
    }

    @Test
    @DisplayName("Slack ids, handles and display names all reach the same person")
    void slackAliasesResolve() {
        assertThat(directory.byAlias(SourceSystem.SLACK, "U_APETROVA"))
                .get()
                .extracting(Person::canonicalName)
                .isEqualTo("Alexandra Petrova");
        assertThat(directory.byAlias(SourceSystem.SLACK, "Alex P."))
                .get()
                .extracting(Person::canonicalName)
                .isEqualTo("Alexandra Petrova");
    }

    @Test
    @DisplayName("Notion authors join to their Slack identity on an exact name")
    void notionAuthorsJoin() {
        assertThat(directory.byAlias(SourceSystem.NOTION, "Alex Chen"))
                .get()
                .extracting(Person::email)
                .isEqualTo("alex.chen@meridian.media");
    }

    @Test
    @DisplayName("the two Alexes stay separate")
    void theTwoAlexesAreNotMerged() {
        List<Person> alexes = directory.byNamePrefix("Alex");

        assertThat(alexes).hasSize(2);
        assertThat(alexes).extracting(Person::email).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("meeting participants are keyed by the episode id the Zoom reader emits")
    void participantsAreKeyedByEpisodeId() {
        // The whole speaker-adjudication path depends on this key matching exactly.
        assertThat(directory.episodesWithParticipants()).contains("zoom:mtg_8f2c41ab9e");
        assertThat(directory.participantsOf("zoom:mtg_8f2c41ab9e"))
                .extracting(Person::canonicalName)
                .containsExactlyInAnyOrder("Alexandra Petrova", "Dana Okonkwo", "Priya Raghunathan");
    }

    @Test
    @DisplayName("participants carry their titles, which is what identifies anonymous speakers")
    void participantsCarryTitles() {
        assertThat(directory.participantsOf("zoom:mtg_8f2c41ab9e"))
                .allSatisfy(person -> assertThat(person.title()).isNotBlank());
    }
}
