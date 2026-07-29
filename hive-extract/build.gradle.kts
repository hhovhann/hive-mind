dependencies {
    implementation(project(":hive-core"))
    implementation(project(":hive-ingest"))
    implementation(libs.spring.boot.starter)
    implementation(libs.langchain4j)
    implementation(libs.langchain4j.open.ai)
    implementation(libs.jackson.databind)
    implementation(libs.jackson.jsr310)
}
