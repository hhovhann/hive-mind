package com.hhovhann.hivemind.extract;

import com.hhovhann.hivemind.core.ontology.FactStatus;
import com.hhovhann.hivemind.core.ontology.FactType;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonEnumSchema;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonNumberSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchema;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import java.util.Arrays;
import java.util.List;

/**
 * Compiles the ontology into the JSON Schema the model is constrained to fill.
 *
 * <p>This is what "the ontology constrains extraction" means in practice: the schema
 * is generated from {@link FactType}, so adding a type or renaming a status changes
 * what the model may emit without anyone editing a prompt. Free-text output parsed
 * with regexes drifts the moment the model does; a schema does not.
 *
 * <p>Every field is marked required, with empty string and empty array standing in
 * for absent. Optional fields sound better and behave worse — strict schema modes
 * demand a complete {@code required} list, and models given the choice between
 * omitting a field and inventing a value pick invention more often than they should.
 */
public final class OntologySchema {

    public static final String SCHEMA_NAME = "extracted_facts";

    private OntologySchema() {}

    public static JsonSchema forExtraction() {
        return JsonSchema.builder()
                .name(SCHEMA_NAME)
                .rootElement(JsonObjectSchema.builder()
                        .addProperty(
                                "facts",
                                JsonArraySchema.builder()
                                        .description("Every fact supported by the transcript. Empty if none are.")
                                        .items(factSchema())
                                        .build())
                        .required("facts")
                        .build())
                .build();
    }

    private static JsonObjectSchema factSchema() {
        return JsonObjectSchema.builder()
                .addProperty(
                        "type",
                        JsonEnumSchema.builder()
                                .enumValues(names(FactType.values()))
                                .description("Which kind of fact this is.")
                                .build())
                .addProperty(
                        "statement",
                        JsonStringSchema.builder()
                                .description("One self-contained sentence. It must make sense to someone who has "
                                        + "not read the conversation, so resolve pronouns and name things explicitly.")
                                .build())
                .addProperty(
                        "owner",
                        JsonStringSchema.builder()
                                .description("Who made this decision or owns this work, exactly as named in the "
                                        + "text. Empty string if the text does not say.")
                                .build())
                .addProperty(
                        "participants",
                        JsonArraySchema.builder()
                                .description("Others involved, as named in the text.")
                                .items(new JsonStringSchema())
                                .build())
                .addProperty(
                        "occurredAt",
                        JsonStringSchema.builder()
                                .description("ISO-8601 instant when this was decided or raised, if the text states "
                                        + "one. Empty string otherwise — do not guess.")
                                .build())
                .addProperty(
                        "dueDate",
                        JsonStringSchema.builder()
                                .description("Deadline as YYYY-MM-DD if the text states one. Resolve relative dates "
                                        + "such as 'Friday' or 'end of Q3' against the conversation date. Empty "
                                        + "string if there is no deadline.")
                                .build())
                .addProperty(
                        "status",
                        JsonEnumSchema.builder()
                                .enumValues(names(FactStatus.values()))
                                .description("PROPOSED for something suggested but not accepted. AGREED once it is "
                                        + "settled. Never SUPERSEDED — you cannot see later conversations.")
                                .build())
                .addProperty(
                        "topics",
                        JsonArraySchema.builder()
                                .description("One to three short subject labels, lowercase.")
                                .items(new JsonStringSchema())
                                .build())
                .addProperty(
                        "evidence",
                        JsonArraySchema.builder()
                                .description("At least one quote proving this fact. Required.")
                                .items(evidenceSchema())
                                .build())
                .addProperty(
                        "confidence",
                        JsonNumberSchema.builder()
                                .description("0 to 1. Below 0.5 if you are inferring rather than reading.")
                                .build())
                .required(
                        "type",
                        "statement",
                        "owner",
                        "participants",
                        "occurredAt",
                        "dueDate",
                        "status",
                        "topics",
                        "evidence",
                        "confidence")
                .build();
    }

    private static JsonObjectSchema evidenceSchema() {
        return JsonObjectSchema.builder()
                .addProperty(
                        "utterance",
                        JsonIntegerSchema.builder()
                                .description("The number in square brackets at the start of the line you quoted.")
                                .build())
                .addProperty(
                        "quote",
                        JsonStringSchema.builder()
                                .description("Text copied character for character from that line. It is checked "
                                        + "against the source; a paraphrase causes the fact to be discarded.")
                                .build())
                .required("utterance", "quote")
                .build();
    }

    private static List<String> names(Enum<?>[] values) {
        return Arrays.stream(values).map(Enum::name).toList();
    }
}
