# Quarkus test JVM retains every test profile's application

A minimal reproducer: Quarkus 3.40.1, one `@ApplicationScoped` bean, and N `@QuarkusTest` classes each naming a
`QuarkusTestProfile` of its own, run in one Gradle test JVM. After every test class, a JUnit `TestExecutionListener`
(`src/test/java/repro/RetentionListener.java`) runs two full GCs. It then records the live `QuarkusClassLoader`s (the
`VM.classloader_stats` diagnostic command, which `jcmd <pid> VM.classloader_stats` runs), the live heap, the long-lived
threads and the registered shutdown hooks, each named with the context classloader it holds.

Two optional features switch on what a real application usually has:

| property | adds | effect in a test JVM |
|---|---|---|
| `-Pdevservices=true` | `quarkus-jdbc-postgresql` | Dev Services starts a PostgreSQL through Testcontainers |
| `-Pinfo=true` | `quarkus-info` | the build step `InfoProcessor.gitInfo` reads this git repository through JGit |

## Run

Docker or Podman is needed for `-Pdevservices=true`. The project must be inside a git repository for `-Pinfo=true` to
reach JGit; a clone of this repository is one. Run from the repository root.

```bash
./gradlew -p test-profile-retention test --tests 'repro.distinct.*'                                 # 12 classes, 12 profiles
./gradlew -p test-profile-retention test --tests 'repro.distinct.*' -Pdevservices=true -Pinfo=true  # ...with Testcontainers and JGit
./gradlew -p test-profile-retention test --tests 'repro.shared.*'                                   # control: 12 classes, one profile
./gradlew -p test-profile-retention test --tests 'repro.distinct.*' -PdumpAfter=P06Test             # ...and a live heap dump in build/
pwsh test-profile-retention/tools/matrix.ps1 -Gradle ./gradlew                                       # N = 1, 3, 6, 12 in every variant
java -Xmx6g test-profile-retention/tools/HeapPaths.java test-profile-retention/build/after-P06Test.hprof --match Deployment --paths 3
```

`build/retention.csv` has one row per test class; `build/retention-details.txt` names every pinning thread and hook.

`tools/HeapPaths.java` is a single-file HPROF reader with no dependencies. It finds the shortest strong path from a
GC root to every `QuarkusClassLoader`. It does not follow `Reference.referent`. `--cut-thread <prefix>` and
`--cut-field <Class#field>` remove one route, to show whether another holds the same loader. A loader still reachable
with a route cut is retained independently of that route. Shortest-path views, Eclipse Memory Analyzer's included,
show only the shortest route, so a short thread-to-context-classloader path hides a longer framework path.

## What it shows

Measured on 14 October 2026: JDK 25.0.2 (Corretto), Gradle 9.7.1, Windows 11, Docker 29.8.1, Quarkus 3.40.1,
Testcontainers 2.0.5 (Ryuk image 0.14.0), JGit 7.7.0.202606012155-r. `results/matrix.csv` holds the rows below.
Live heap is after two full GCs at the end of the last test class.

| variant | 1 profile | 3 | 6 | 12 | 12 classes, 1 profile (control) | per extra profile |
|---|---|---|---|---|---|---|
| baseline | 31,931 KB | 43,693 | 62,982 | 101,139 | 31,273 | ~6.3 MB |
| `-Pdevservices=true` | 48,299 | 75,009 | 115,935 | 198,070 | 47,565 | ~13.6 MB |
| `-Pinfo=true` | 44,397 | 69,981 | 109,736 | 189,278 | 43,606 | ~13.2 MB |
| both | 60,156 | 99,516 | 159,701 | 280,244 | 59,259 | ~20.0 MB |

- Live `QuarkusClassLoader`s are 4 per profile in every variant (4, 12, 24, 48) and 4 for the control, already after the
  first test class: every profile is augmented during JUnit discovery, before any test runs.
- With Dev Services, one `testcontainers-ryuk` thread and one Ryuk container per profile that has run (12 Ryuk
  containers at once for 12 profiles; Ryuk removed them all when the JVM exited).
- With quarkus-info, one `JGit-WorkQueue` thread and one JGit `CleanupService` shutdown hook per profile, all 12
  present after the first test class.
- `results/paths-*.txt`: the routes that hold each profile's Deployment Class Loader, in one live heap dump taken
  after the 6th of 12 classes (both features on), with routes cut in turn.
