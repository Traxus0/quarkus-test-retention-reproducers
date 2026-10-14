# Quarkus test-JVM retention reproducers

Small, synthetic reproducers for three reports. Each directory is its own Gradle build, run with the wrapper at the
root. Measured on 14 October 2026 with Quarkus 3.40.1, quarkus-temporal 0.6.0, Testcontainers 2.0.5 (Ryuk image
0.14.0), JGit 7.7.0.202606012155-r, Gradle 9.7.1, JDK 25.0.2 (Amazon Corretto), Docker 29.8.1 on Windows 11.
Each directory's `results/` holds the text output those runs produced.

| directory | shows | command |
|---|---|---|
| `test-profile-retention/` | every `@TestProfile`'s application stays reachable for the whole test run, through several independent routes | `./gradlew -p test-profile-retention test --tests 'repro.distinct.*'` (see its README) |
| `temporal-registrations/` | quarkus-temporal's `WorkerRegistrationRegistry` gains one entry per start, and each start leaves a `grpc-connection-manager-thread` | `./gradlew -p temporal-registrations test` |
| `temporal-annotation-default/` | `@TemporalWorkflow` / `@TemporalActivity` without `workers` fails the build with a `NullPointerException` | `./gradlew -p temporal-annotation-default quarkusBuild -Pcase=workflow` |

`test-profile-retention/` needs Docker or Podman for `-Pdevservices=true`. It needs to be inside a git repository
for `-Pinfo=true` to reach JGit; a clone of this repository is one. `temporal-registrations/` needs no Temporal
server.

No heap dump is published. `test-profile-retention/tools/HeapPaths.java` reads one you take yourself
(`-PdumpAfter=P06Test`). It is a single-file HPROF reader that finds the shortest strong path from a GC root to each
`QuarkusClassLoader`, and it can remove one route at a time.
