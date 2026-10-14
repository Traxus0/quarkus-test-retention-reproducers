package repro.shared;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import repro.Greeting;

/** The control: the same profile as every other class here. */
@QuarkusTest
@TestProfile(SharedProfile.class)
class S03Test {

    @Inject
    Greeting greeting;

    @Test
    void starts() {
        assertEquals("hello", greeting.hello());
    }
}
