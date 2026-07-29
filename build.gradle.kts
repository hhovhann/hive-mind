plugins {
    java
    alias(libs.plugins.spring.boot) apply false
}

// Version-catalog accessors are only generated for the script's own scope, so
// capture what the subprojects block needs before entering it.
val javaVersion = libs.versions.java.get().toInt()
val springBootBom = libs.spring.boot.bom
val langchain4jBom = libs.langchain4j.bom
val testcontainersBom = libs.testcontainers.bom
val springBootStarterTest = libs.spring.boot.starter.test
val assertjCore = libs.assertj
val junitPlatformLauncher = libs.junit.platform.launcher

allprojects {
    group = "com.hhovhann.hivemind"
    version = "0.1.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "java")

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion = JavaLanguageVersion.of(javaVersion)
        }
    }

    dependencies {
        // Platforms give every module the same managed versions without the
        // dependency-management plugin. Module build files declare no versions.
        add("implementation", platform(springBootBom))
        add("implementation", platform(langchain4jBom))
        add("annotationProcessor", platform(springBootBom))
        add("testImplementation", platform(springBootBom))
        add("testImplementation", platform(testcontainersBom))

        add("testImplementation", springBootStarterTest)
        add("testImplementation", assertjCore)
        add("testRuntimeOnly", junitPlatformLauncher)
    }

    tasks.withType<JavaCompile>().configureEach {
        options.compilerArgs.addAll(listOf("-Xlint:all", "-parameters"))
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        // Tests run against the real sample corpus, which lives at the repo root
        // rather than inside any one module.
        systemProperty("hive.repo.root", rootProject.projectDir.absolutePath)
        testLogging {
            events("passed", "skipped", "failed")
        }
    }
}
