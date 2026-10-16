package sn.gouv.mfp.workflow.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Transition refusée car le dossier n'est plus actif (COMPLETED / CANCELLED),
 * typiquement après une validation concurrente → HTTP 409 Conflict.
 */
@ResponseStatus(HttpStatus.CONFLICT)
public class WorkflowConflictException extends RuntimeException {

    public WorkflowConflictException(String message) {
        super(message);
    }
}
