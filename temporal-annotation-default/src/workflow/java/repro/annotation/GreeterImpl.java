package repro.annotation;

import io.quarkiverse.temporal.TemporalActivity;
import jakarta.enterprise.context.ApplicationScoped;


@ApplicationScoped
public class GreeterImpl implements Greeter {

    @Override
    public String greet() {
        return "hi";
    }
}
