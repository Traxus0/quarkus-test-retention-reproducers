package repro.temporal;

/**
 * One workflow implementation. quarkus-temporal builds a worker for every @WorkflowInterface implementation in the
 * index, so WorkerFactoryRecorder.createWorker runs once per start.
 */
public class HelloImpl implements Hello {

    @Override
    public String hello() {
        return "hello";
    }
}
