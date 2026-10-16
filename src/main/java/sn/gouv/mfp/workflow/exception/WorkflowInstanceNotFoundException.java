package sn.gouv.mfp.workflow.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** Instance de workflow introuvable → HTTP 404 Not Found. */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class WorkflowInstanceNotFoundException extends WorkflowNotFoundException {

    public WorkflowInstanceNotFoundException(String message) {
        super(message);
    }
}
