package repro.temporal;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.main.Launch;
import io.quarkus.test.junit.main.LaunchResult;
import io.quarkus.test.junit.main.QuarkusMainTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * Five launches in one profile. Each start runs WorkerFactoryRecorder.createWorker once. If the registry held only the
 * running application's registration, every launch would print registrations=1. It prints 1, 2, 3, 4, 5.
 */
@QuarkusMainTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LaunchTest {

    @Test @Order(1) @Launch void first(LaunchResult r) { report(r); }
    @Test @Order(2) @Launch void second(LaunchResult r) { report(r); }
    @Test @Order(3) @Launch void third(LaunchResult r) { report(r); }
    @Test @Order(4) @Launch void fourth(LaunchResult r) { report(r); }
    @Test @Order(5) @Launch void fifth(LaunchResult r) { report(r); }

    private static void report(LaunchResult result) {
        String line = result.getOutputStream().stream().filter(l -> l.startsWith("registrations=")).findFirst().orElse("none");
        System.out.println("[temporal-repro] " + line);
        result.getOutputStream().stream().filter(l -> l.startsWith("threads=")).forEach(l -> System.out.println("[temporal-repro] " + l));
        assertTrue(line.startsWith("registrations="), line);
    }
}
