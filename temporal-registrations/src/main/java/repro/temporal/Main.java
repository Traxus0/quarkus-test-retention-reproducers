package repro.temporal;

import io.quarkiverse.temporal.WorkerRegistrationRegistry;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import java.lang.reflect.Field;
import java.util.List;

/** Prints how many registrations quarkus-temporal's static registry holds, and the classloader each start runs in. */
@QuarkusMain
public class Main implements QuarkusApplication {

    @Override
    public int run(String... args) throws Exception {
        Field field = WorkerRegistrationRegistry.class.getDeclaredField("REGISTRATIONS");
        field.setAccessible(true);
        List<?> registrations = (List<?>) field.get(null);
        System.out.println("registrations=" + registrations.size()
                + " registry-loader=" + WorkerRegistrationRegistry.class.getClassLoader().getName()
                + " app-loader=" + Main.class.getClassLoader().getName());
        java.util.Map<String, Long> threads = Thread.getAllStackTraces().keySet().stream()
                .map(t -> t.getName().replaceAll("[-#]?\\d+$", "#n"))
                .collect(java.util.stream.Collectors.groupingBy(n -> n, java.util.TreeMap::new, java.util.stream.Collectors.counting()));
        System.out.println("threads=" + threads);
        return 0;
    }
}
