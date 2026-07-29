dependencies {
    implementation(project(":hive-core"))
    implementation(libs.spring.boot.starter)
    implementation(libs.neo4j.driver)
    implementation(libs.langchain4j)

    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.neo4j)
}
