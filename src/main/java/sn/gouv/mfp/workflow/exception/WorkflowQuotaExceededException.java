package sn.gouv.mfp.workflow.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Plafond de dossiers actifs atteint pour un initiateur
 * (settings maxActiveInstancesPerInitiator) → HTTP 409 Conflict.
 */
@ResponseStatus(HttpStatus.CONFLICT)
public class WorkflowQuotaExceededException extends RuntimeException {

    public WorkflowQuotaExceededException(String message) {
        super(message);
    }
}
