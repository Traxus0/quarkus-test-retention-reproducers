// @TemporalWorkflow / @TemporalActivity with the default `workers` (an empty array) fails the build.
//   -Pcase=workflow  annotates the workflow implementation
//   -Pcase=activity  annotates the activity implementation
//   -Pcase=explicit  names the worker explicitly (the control, builds)
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
}

java {
    toolchain { languageVersion = JavaLanguageVersion.of(25) }
}

val case = providers.gradleProperty("case").getOrElse("workflow")
sourceSets["main"].java.srcDir("src/$case/java")
