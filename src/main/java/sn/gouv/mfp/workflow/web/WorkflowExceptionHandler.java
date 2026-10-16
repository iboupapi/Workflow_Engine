package sn.gouv.mfp.workflow.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import sn.gouv.mfp.workflow.exception.InvalidEntryPointException;
import sn.gouv.mfp.workflow.exception.MissingRequiredDataException;
import sn.gouv.mfp.workflow.exception.WorkflowConflictException;
import sn.gouv.mfp.workflow.exception.WorkflowForbiddenException;
import sn.gouv.mfp.workflow.exception.WorkflowNotFoundException;
import sn.gouv.mfp.workflow.exception.WorkflowQuotaExceededException;
import sn.gouv.mfp.workflow.exception.WorkflowValidationException;

/**
 * Contrat d'erreur explicite de l'API (cahier de recette : un refus doit toujours être
 * explicite). Chaque exception métier est convertie en statut HTTP avec son message
 * en corps de réponse — sans trace technique (12.4).
 */
@RestControllerAdvice
public class WorkflowExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(WorkflowExceptionHandler.class);

    @ExceptionHandler(WorkflowNotFoundException.class)
    public ResponseEntity<ErrorBody> notFound(WorkflowNotFoundException e) {
        return refuse(HttpStatus.NOT_FOUND, e);
    }

    @ExceptionHandler(WorkflowForbiddenException.class)
    public ResponseEntity<ErrorBody> forbidden(WorkflowForbiddenException e) {
        return refuse(HttpStatus.FORBIDDEN, e);
    }

    @ExceptionHandler({WorkflowConflictException.class, WorkflowQuotaExceededException.class})
    public ResponseEntity<ErrorBody> conflict(RuntimeException e) {
        return refuse(HttpStatus.CONFLICT, e);
    }

    @ExceptionHandler({
        WorkflowValidationException.class,
        MissingRequiredDataException.class,
        InvalidEntryPointException.class
    })
    public ResponseEntity<ErrorBody> badRequest(RuntimeException e) {
        return refuse(HttpStatus.BAD_REQUEST, e);
    }

    private ResponseEntity<ErrorBody> refuse(HttpStatus status, RuntimeException e) {
        log.info("Refus {} : {}", status.value(), e.getMessage());
        return ResponseEntity.status(status)
                .body(new ErrorBody(e.getClass().getSimpleName(), e.getMessage()));
    }

    /** Corps d'erreur stable : code d'exception et message explicite, sans détail interne. */
    public record ErrorBody(String code, String message) {
    }
}
