package sn.gouv.mfp.workflow.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Pièce jointe trop volumineuse (FRM-05 : taille maximale) — HTTP 413 Payload Too Large.
 */
@ResponseStatus(HttpStatus.PAYLOAD_TOO_LARGE)
public class WorkflowPayloadTooLargeException extends RuntimeException {

    public WorkflowPayloadTooLargeException(String message) {
        super(message);
    }
}
