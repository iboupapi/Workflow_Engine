package sn.gouv.mfp.workflow.exception;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Données de formulaire non conformes au JSON Schema publié du type de demande (FRM-03)
 * → HTTP 400 Bad Request.
 * <p>
 * Les écarts détectés sont conservés tels quels ({@link #getViolations()}) pour être
 * renvoyés à l'usager : en cas d'échec, aucune donnée n'est enregistrée.
 */
@ResponseStatus(HttpStatus.BAD_REQUEST)
public class FormSchemaValidationException extends RuntimeException {

    private final transient List<String> violations;

    public FormSchemaValidationException(String message, List<String> violations) {
        super(message);
        this.violations = List.copyOf(violations);
    }

    public FormSchemaValidationException(String message) {
        this(message, List.of());
    }

    /** Écarts constatés (emplacement puis message), liste vide si l'échec n'est pas un écart de données. */
    public List<String> getViolations() {
        return violations;
    }
}
