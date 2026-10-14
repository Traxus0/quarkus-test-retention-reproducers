package repro.annotation;

import io.temporal.activity.ActivityInterface;

@ActivityInterface
public interface Greeter {

    String greet();
}
