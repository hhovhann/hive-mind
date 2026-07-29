dependencies {
    implementation(project(":hive-core"))
    implementation(project(":hive-graph"))
    implementation(libs.spring.boot.starter)
    implementation(libs.langchain4j)
    implementation(libs.langchain4j.open.ai)
    implementation(libs.neo4j.driver)
}
