package sn.gouv.mfp.workflow.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** Aucune transition valide pour l'action demandée (condition non remplie, circuit mal configuré) → HTTP 400. */
@ResponseStatus(HttpStatus.BAD_REQUEST)
public class WorkflowValidationException extends RuntimeException {

    public WorkflowValidationException(String message) {
        super(message);
    }
}
