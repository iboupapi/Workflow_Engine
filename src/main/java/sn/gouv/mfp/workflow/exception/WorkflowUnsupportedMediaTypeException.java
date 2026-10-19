package sn.gouv.mfp.workflow.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Type de pièce jointe non autorisé (FRM-05 : liste des types autorisés) —
 * HTTP 415 Unsupported Media Type.
 * <p>
 * Le refus porte sur le type MIME <strong>réel</strong>, déduit du contenu : un fichier
 * annoncé comme PDF mais dont le contenu ne l'est pas est refusé comme tout autre type
 * non autorisé.
 */
@ResponseStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
public class WorkflowUnsupportedMediaTypeException extends RuntimeException {

    public WorkflowUnsupportedMediaTypeException(String message) {
        super(message);
    }
}
