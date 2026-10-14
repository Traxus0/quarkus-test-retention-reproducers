package repro;

import jakarta.enterprise.context.ApplicationScoped;

/** The whole application: one bean, so each test has something to inject. */
@ApplicationScoped
public class Greeting {

    public String hello() {
        return "hello";
    }
}
