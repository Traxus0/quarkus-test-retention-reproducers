package repro.annotation;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

@WorkflowInterface
public interface Hello {

    @WorkflowMethod
    String hello();
}
