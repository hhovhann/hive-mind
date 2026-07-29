dependencies {
    implementation(project(":hive-core"))
    implementation(project(":hive-ingest"))
    implementation(project(":hive-extract"))
    implementation(project(":hive-retrieval"))
    implementation(libs.spring.boot.starter)
    implementation(libs.langchain4j)
    implementation(libs.jackson.databind)
    implementation(libs.jackson.jsr310)
}
