package sn.gouv.mfp.workflow.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Donnée obligatoire de l'étape (requiredData) absente du dossier
 * lors d'une validation → HTTP 400 Bad Request.
 */
@ResponseStatus(HttpStatus.BAD_REQUEST)
public class MissingRequiredDataException extends RuntimeException {

    public MissingRequiredDataException(String message) {
        super(message);
    }
}
