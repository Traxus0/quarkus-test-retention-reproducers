package repro.annotation;

import io.quarkiverse.temporal.TemporalActivity;
import jakarta.enterprise.context.ApplicationScoped;

@TemporalActivity
@ApplicationScoped
public class GreeterImpl implements Greeter {

    @Override
    public String greet() {
        return "hi";
    }
}
