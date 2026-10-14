package repro.annotation;

import io.quarkiverse.temporal.TemporalWorkflow;


public class HelloImpl implements Hello {

    @Override
    public String hello() {
        return "hello";
    }
}
