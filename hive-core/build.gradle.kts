// Pure domain: entities, ontology, ports. No Spring, no framework — so the
// model stays testable and the rules live somewhere a reader can find them.
dependencies {
    implementation(libs.jackson.databind)
    implementation(libs.jackson.jsr310)
}
