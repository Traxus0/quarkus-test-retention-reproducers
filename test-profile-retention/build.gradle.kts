// Minimal reproducer: classloaders retained per @TestProfile test class in one test JVM.
//
// Variants, each a Gradle property, so one source tree measures every combination:
//   -Pdevservices=true   adds quarkus-jdbc-postgresql, so Dev Services starts a PostgreSQL through Testcontainers
//   -Pinfo=true          adds quarkus-info, whose build step reads the enclosing git repository through JGit
//   -Pheap=2g            the test JVM's maximum heap (default 1g)
// Select the test classes with --tests: repro.distinct.* uses one profile per class (one curated application each),
// repro.shared.* uses the same profile in every class (the control).

plugins {
    java
    id("io.quarkus") version "3.40.1"
}

repositories {
    mavenCentral()
}

val devServices = providers.gradleProperty("devservices").map(String::toBoolean).getOrElse(false)
val info = providers.gradleProperty("info").map(String::toBoolean).getOrElse(false)
val heap = providers.gradleProperty("heap").getOrElse("1g")

dependencies {
    implementation(enforcedPlatform("io.quarkus.platform:quarkus-bom:3.40.1"))
    implementation("io.quarkus:quarkus-arc")
    if (devServices) implementation("io.quarkus:quarkus-jdbc-postgresql")
    if (info) implementation("io.quarkus:quarkus-info")
    testImplementation("io.quarkus:quarkus-junit")
}

java {
    toolchain { languageVersion = JavaLanguageVersion.of(25) }
}

tasks.test {
    useJUnitPlatform()
    maxHeapSize = heap
    systemProperty("java.util.logging.manager", "org.jboss.logmanager.LogManager")
    systemProperty("retention.report", layout.buildDirectory.file("retention.csv").get().asFile.absolutePath)
    systemProperty("retention.variant", "devservices=$devServices info=$info heap=$heap")
    // The listener reads java.lang.ApplicationShutdownHooks to name each registered hook and the loader it holds.
    jvmArgs("--add-opens", "java.base/java.lang=ALL-UNNAMED")
    if (providers.gradleProperty("xlog").isPresent) {
        jvmArgs("-Xlog:class+load=info,class+unload=info:file=" + layout.buildDirectory.file("classlog.txt").get().asFile.absolutePath)
    }
    providers.gradleProperty("dumpAfter").orNull?.let { systemProperty("retention.dumpAfter", it) }
    systemProperty("retention.hierarchy", providers.gradleProperty("hierarchy").getOrElse("false"))
    testLogging { showStandardStreams = true }
    outputs.upToDateWhen { false }
}
