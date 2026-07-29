package com.hhovhann.hivemind.core.ontology;

import java.util.List;

/**
 * The shared schema every agent extracts into, and its version.
 *
 * <p>Versioning is not ceremony. Extraction quality is measured per prompt and per
 * schema; without a version stamped on each fact there is no way to tell an
 * improvement from a regression, or to re-extract only what an older schema
 * produced. The version travels with the fact into the graph.
 *
 * <p>Bump the minor version when a type or field is added, the major version when
 * the meaning of an existing one changes — the second case invalidates comparisons
 * against earlier eval runs, and should hurt enough to be deliberate.
 */
public final class Ontology {

    public static final String VERSION = "1.0.0";

    private Ontology() {}

    public static List<FactType> types() {
        return List.of(FactType.values());
    }
}
