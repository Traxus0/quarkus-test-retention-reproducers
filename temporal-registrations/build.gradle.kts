// Minimal reproducer: quarkus-temporal's WorkerRegistrationRegistry keeps one entry per start, for ever.
// One @QuarkusMainTest class launches the application several times in one profile, so every launch shares one base
// runtime classloader, as dev-mode live reloads do. The application prints how many registrations the registry holds.

plugins {
    java
    id("io.quarkus") version "3.40.1"
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(enforcedPlatform("io.quarkus.platform:quarkus-bom:3.40.1"))
    implementation("io.quarkus:quarkus-arc")
    implementation("io.quarkiverse.temporal:quarkus-temporal:0.6.0")
    testImplementation("io.quarkus:quarkus-junit")
}

java {
    toolchain { languageVersion = JavaLanguageVersion.of(25) }
}

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "1g"
    systemProperty("java.util.logging.manager", "org.jboss.logmanager.LogManager")
    testLogging { showStandardStreams = true }
    outputs.upToDateWhen { false }
}
