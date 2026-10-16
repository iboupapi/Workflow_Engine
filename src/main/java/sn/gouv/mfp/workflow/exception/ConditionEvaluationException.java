package sn.gouv.mfp.workflow.exception;

/**
 * Échec d'évaluation d'une condition SpEL à l'exécution (syntaxe devenue invalide,
 * déréférencement impossible, type inattendu). Le moteur la convertit en refus explicite
 * de la transition : le dossier reste inchangé et ne bascule jamais sur une transition
 * par défaut (cahier de recette 1.7).
 */
public class ConditionEvaluationException extends RuntimeException {

    private final String conditionExpression;

    public ConditionEvaluationException(String conditionExpression, Throwable cause) {
        super("Évaluation impossible de la condition '" + conditionExpression + "' : "
                + (cause != null ? cause.getMessage() : "cause inconnue"), cause);
        this.conditionExpression = conditionExpression;
    }

    public String getConditionExpression() {
        return conditionExpression;
    }
}
