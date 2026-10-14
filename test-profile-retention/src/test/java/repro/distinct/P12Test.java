package repro.distinct;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.util.Map;
import org.junit.jupiter.api.Test;
import repro.Greeting;

/** A profile of its own, so Quarkus builds this class a curated application of its own. */
@QuarkusTest
@TestProfile(P12Test.Profile.class)
class P12Test {

    public static class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("repro.profile", "12");
        }
    }

    @Inject
    Greeting greeting;

    @Test
    void starts() {
        assertEquals("hello", greeting.hello());
    }
}
