package sn.gouv.mfp.workflow.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Point d'entrée demandé non autorisé (étape TASK absente des entryPoints
 * de la définition) → HTTP 400 Bad Request.
 */
@ResponseStatus(HttpStatus.BAD_REQUEST)
public class InvalidEntryPointException extends RuntimeException {

    public InvalidEntryPointException(String message) {
        super(message);
    }
}
