package repro.shared;

import io.quarkus.test.junit.QuarkusTestProfile;
import java.util.Map;

/** The one profile every class in this package names: one curated application for all of them. */
public class SharedProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of("repro.profile", "shared");
    }
}
