package sn.gouv.mfp.workflow.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** Opération interdite au demandeur authentifié (vue complète, dossier hors périmètre). */
@ResponseStatus(HttpStatus.FORBIDDEN)
public class WorkflowForbiddenException extends RuntimeException {

    public WorkflowForbiddenException(String message) {
        super(message);
    }
}
