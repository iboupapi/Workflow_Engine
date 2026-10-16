package sn.gouv.mfp.workflow.service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.expression.EvaluationException;
import org.springframework.expression.Expression;
import org.springframework.expression.ParseException;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.SimpleEvaluationContext;
import org.springframework.stereotype.Service;
import sn.gouv.mfp.workflow.exception.ConditionEvaluationException;

/**
 * Étape 2 — résolveur de règles métier (SpEL).
 * <p>
 * Évalue dynamiquement une condition textuelle contre les variables courantes d'un dossier
 * (contenu du JSONB), pour décider si une transition conditionnelle est franchissable.
 * <p>
 * Exemples supportés :
 * <ul>
 *   <li>{@code #variables['circuit'] == 'COURT'}</li>
 *   <li>{@code #variables['natureActe'] == 'ARRETE' and #variables['circuit'] == 'LONG'}</li>
 *   <li>{@code #variables['solde_valide'] == true}</li>
 * </ul>
 * <p>
 * Pur Java, sans accès base de données. Le {@link SpelExpressionParser} est une instance unique
 * (coûteux à créer) et les expressions parsées sont mises en cache : le parsing n'a lieu
 * qu'une fois par condition distincte.
 * <p>
 * Sécurité : l'évaluation a lieu dans un {@code SimpleEvaluationContext} en lecture seule
 * (aucune référence de type, instanciation, appel de méthode ou affectation possible),
 * en complément du contrôle à l'import ({@link SpelSecurityValidator}).
 * <p>
 * Deux modes : {@link #evaluate} tolérant (erreur =&gt; {@code false}, transition bloquée
 * silencieusement) et {@link #evaluateStrict} strict (erreur =&gt; exception, pour que le
 * moteur refuse explicitement l'action au lieu de basculer sur une transition par défaut).
 */
@Service
public class ConditionEvaluatorService {

    private static final Logger log = LoggerFactory.getLogger(ConditionEvaluatorService.class);

    private final SpelExpressionParser parser = new SpelExpressionParser();

    private final ConcurrentMap<String, Expression> expressionCache = new ConcurrentHashMap<>();

    /**
     * Évalue une condition SpEL contre les variables d'un dossier.
     *
     * @param conditionExpression expression SpEL (ex. {@code #variables['circuit'] == 'COURT'}),
     *                            {@code null} ou vide si la transition est inconditionnelle
     * @param variables           variables courantes du dossier (contenu du JSONB)
     * @return {@code true} si la transition est franchissable, {@code false} sinon
     *         (y compris en cas d'expression invalide ou d'erreur d'évaluation)
     */
    public boolean evaluate(String conditionExpression, Map<String, Object> variables) {
        try {
            return Boolean.TRUE.equals(evaluateStrict(conditionExpression, variables));
        } catch (ConditionEvaluationException e) {
            return false;
        }
    }

    /**
     * Évalue une condition SpEL en mode strict : toute erreur de parsing ou d'évaluation
     * (variable absente, type inattendu, ...) lève {@link ConditionEvaluationException}
     * au lieu de retourner {@code false}.
     *
     * @return le verdict booléen, ou {@code true} si la condition est nulle ou vide
     */
    public Boolean evaluateStrict(String conditionExpression, Map<String, Object> variables) {
        if (conditionExpression == null || conditionExpression.isBlank()) {
            return true;
        }
        String normalized = SpelSecurityValidator.normalize(conditionExpression);
        try {
            Expression expression = expressionCache.computeIfAbsent(
                    normalized, parser::parseExpression);
            SimpleEvaluationContext context =
                    SimpleEvaluationContext.forReadOnlyDataBinding().build();
            context.setVariable("variables", variables != null ? variables : Map.of());
            return expression.getValue(context, Boolean.class);
        } catch (ParseException | EvaluationException e) {
            log.warn("Évaluation SpEL impossible '{}' : {} — transition refusée.",
                    normalized, e.getMessage());
            throw new ConditionEvaluationException(normalized, e);
        }
    }
}
