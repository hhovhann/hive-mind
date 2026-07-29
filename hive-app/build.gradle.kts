plugins {
    alias(libs.plugins.spring.boot)
}

dependencies {
    implementation(project(":hive-core"))
    implementation(project(":hive-ingest"))
    implementation(project(":hive-extract"))
    implementation(project(":hive-graph"))
    implementation(project(":hive-retrieval"))
    implementation(project(":hive-eval"))

    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.langchain4j)
    implementation(libs.langchain4j.open.ai)
    implementation(libs.langchain4j.http.jdk)
    implementation(libs.neo4j.driver)
    implementation(libs.spring.boot.neo4j)
    implementation(libs.jackson.databind)
    implementation(libs.jackson.jsr310)
    implementation(libs.mcp.core)
    implementation(libs.mcp.json.jackson2)
    implementation(libs.mcp.spring.webmvc)
}

tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    // The corpus and eval-runs/ live at the repo root, not inside this module.
    workingDir = rootProject.projectDir
    // Java 21 virtual threads: every request here is I/O-bound on an LLM or on
    // Neo4j, so platform threads would be idle capital.
    jvmArgs("-Dspring.threads.virtual.enabled=true")
    // The `mcp` command reads JSON-RPC from stdin, which Gradle otherwise closes
    // immediately. Note that Gradle still owns stdout here, so this is for poking at
    // the server by hand — a real client should launch the built jar.
    standardInput = System.`in`
}
