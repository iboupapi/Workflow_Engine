package sn.gouv.mfp.workflow.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** Dossier, définition ou étape introuvable → HTTP 404. */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class WorkflowNotFoundException extends RuntimeException {

    public WorkflowNotFoundException(String message) {
        super(message);
    }
}
