package sn.gouv.mfp.workflow.service;

import java.util.Set;
import org.springframework.expression.ParseException;
import org.springframework.expression.spel.SpelNode;
import org.springframework.expression.spel.ast.Assign;
import org.springframework.expression.spel.ast.BeanReference;
import org.springframework.expression.spel.ast.CompoundExpression;
import org.springframework.expression.spel.ast.ConstructorReference;
import org.springframework.expression.spel.ast.Elvis;
import org.springframework.expression.spel.ast.FunctionReference;
import org.springframework.expression.spel.ast.Identifier;
import org.springframework.expression.spel.ast.Indexer;
import org.springframework.expression.spel.ast.Literal;
import org.springframework.expression.spel.ast.MethodReference;
import org.springframework.expression.spel.ast.OpAnd;
import org.springframework.expression.spel.ast.OpEQ;
import org.springframework.expression.spel.ast.OpGE;
import org.springframework.expression.spel.ast.OpGT;
import org.springframework.expression.spel.ast.OpLE;
import org.springframework.expression.spel.ast.OpLT;
import org.springframework.expression.spel.ast.OpNE;
import org.springframework.expression.spel.ast.OpOr;
import org.springframework.expression.spel.ast.OperatorMatches;
import org.springframework.expression.spel.ast.OperatorNot;
import org.springframework.expression.spel.ast.PropertyOrFieldReference;
import org.springframework.expression.spel.ast.Ternary;
import org.springframework.expression.spel.ast.TypeReference;
import org.springframework.expression.spel.ast.VariableReference;
import org.springframework.expression.spel.standard.SpelExpression;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import sn.gouv.mfp.workflow.exception.WorkflowValidationException;

/**
 * Garde-fou de sécurité des conditions SpEL (cahier de recette, section 1).
 * <p>
 * Toute condition est normalisée puis inspectée AVANT persistance (import YAML) et le moteur
 * n'évalue qu'en contexte lecture seule : exécution de code, instanciation, appels de méthodes
 * et affectations sont impossibles, même si une condition malveillante contournait l'import.
 * <p>
 * Syntaxe canonique : {@code #variables['cle']}. L'écriture historique {@code variables['cle']}
 * (sans {@code #}) est acceptée à l'import et normalisée automatiquement.
 * <p>
 * Constructions autorisées : littéraux, {@code #variables} (et lui seul) avec accès
 * indexé {@code [...]}, comparaisons ({@code ==}, {@code !=}, {@code <}, {@code <=},
 * {@code >}, {@code >=}), logique ({@code and}, {@code or}, {@code !}), ternaire et Elvis.
 */
public final class SpelSecurityValidator {

    /** Taille maximale d'une condition (recette 1.10). */
    public static final int MAX_CONDITION_LENGTH = 500;

    /** Profondeur maximale d'imbrication des parenthèses et crochets (recette 1.10). */
    public static final int MAX_NESTING_DEPTH = 10;

    private static final String VARIABLES = "variables";
    private static final String CANONICAL_VARIABLE = "#variables";

    private static final SpelExpressionParser PARSER = new SpelExpressionParser();

    private SpelSecurityValidator() {
    }

    /**
     * Normalise une condition : convertit l'écriture historique {@code variables['cle']} en
     * {@code #variables['cle']}, sans toucher aux littéraux entre quotes. Idempotent.
     */
    public static String normalize(String expression) {
        if (expression == null) {
            return null;
        }
        StringBuilder out = new StringBuilder(expression.length() + 8);
        boolean inSingle = false;
        boolean inDouble = false;
        int i = 0;
        int n = expression.length();
        while (i < n) {
            char c = expression.charAt(i);
            if (c == '\'' && !inDouble) {
                if (inSingle && i + 1 < n && expression.charAt(i + 1) == '\'') {
                    out.append("''");
                    i += 2;
                    continue;
                }
                inSingle = !inSingle;
                out.append(c);
                i++;
                continue;
            }
            if (c == '"' && !inSingle) {
                inDouble = !inDouble;
                out.append(c);
                i++;
                continue;
            }
            if (!inSingle
                    && !inDouble
                    && matchesWordAt(expression, i, VARIABLES)
                    && !isWordCharBefore(expression, i)
                    && !isWordCharAfter(expression, i + VARIABLES.length())) {
                out.append(CANONICAL_VARIABLE);
                i += VARIABLES.length();
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    /**
     * Valide une condition et renvoie sa forme canonique normalisée.
     *
     * @param condition          condition brute (peut être null ou vide : retournée telle quelle)
     * @param declaredVariables  clés autorisées dans les conditions, ou null/vide pour
     *                           ne pas contrôler (bloc {@code variables:} YAML absent)
     * @throws WorkflowValidationException (HTTP 400) si la condition est dangereuse,
     *                                     mal formée ou hors limites
     */
    public static String validate(String condition, Set<String> declaredVariables) {
        if (condition == null || condition.isBlank()) {
            return condition;
        }
        if (condition.length() > MAX_CONDITION_LENGTH) {
            throw new WorkflowValidationException("Condition SpEL refusée : taille "
                    + condition.length() + " supérieure à la limite de " + MAX_CONDITION_LENGTH
                    + " caractères.");
        }
        String normalized = normalize(condition);
        if (maxNestingDepth(normalized) > MAX_NESTING_DEPTH) {
            throw new WorkflowValidationException("Condition SpEL refusée : imbrication "
                    + "supérieure à la limite de " + MAX_NESTING_DEPTH + " niveaux.");
        }
        SpelNode ast;
        try {
            ast = ((SpelExpression) PARSER.parseExpression(normalized)).getAST();
        } catch (ParseException e) {
            throw new WorkflowValidationException(
                    "Condition SpEL invalide '" + condition + "' : " + e.getMessage());
        }
        String denial = findDenial(ast);
        if (denial != null) {
            throw refused(denial);
        }
        if (!containsVariablesReference(ast)) {
            throw refused("aucune référence à #variables['cle'] dans la condition");
        }
        inspect(ast, declaredVariables);
        return normalized;
    }

    /** Recherche en profondeur la première construction interdite (messages précis). */
    private static String findDenial(SpelNode node) {
        if (node instanceof TypeReference) {
            return "référence de type T(...) interdite (exécution de code)";
        }
        if (node instanceof ConstructorReference) {
            return "instanciation avec 'new' interdite";
        }
        if (node instanceof MethodReference) {
            return "appel de méthode interdit — conditions en lecture seule";
        }
        if (node instanceof Assign) {
            return "affectation interdite — conditions en lecture seule";
        }
        if (node instanceof BeanReference || node instanceof FunctionReference) {
            return "référence '" + node.toStringAST() + "' interdite";
        }
        if (node instanceof Identifier) {
            return "identifiant '" + node.toStringAST()
                    + "' inconnu — utilisez #variables['cle']";
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            String denial = findDenial(node.getChild(i));
            if (denial != null) {
                return denial;
            }
        }
        return null;
    }

    private static boolean containsVariablesReference(SpelNode node) {
        if (node instanceof VariableReference) {
            return true;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            if (containsVariablesReference(node.getChild(i))) {
                return true;
            }
        }
        return false;
    }

    private static void inspect(SpelNode node, Set<String> declaredVariables) {
        if (node instanceof Literal) {
            return;
        }
        if (node instanceof VariableReference) {
            if (!CANONICAL_VARIABLE.equals(node.toStringAST())) {
                throw refused("variable '" + node.toStringAST()
                        + "' inconnue — utilisez #variables['cle']");
            }
        } else if (node instanceof Indexer || node instanceof PropertyOrFieldReference) {
            if ("class".equalsIgnoreCase(linkKey(node))) {
                throw refused("accès '.class' interdit (porte d'échappement vers le réflectif)");
            }
            checkDirectKey(node, declaredVariables);
        } else if (node instanceof CompoundExpression) {
            // Chaîne d'accès (ex. #variables['cle']) : contrôle de la clé de premier niveau.
            checkCompoundChain(node, declaredVariables);
        } else if (node instanceof OpEQ
                || node instanceof OpNE
                || node instanceof OpLT
                || node instanceof OpLE
                || node instanceof OpGT
                || node instanceof OpGE
                || node instanceof OpAnd
                || node instanceof OpOr
                || node instanceof OperatorNot
                || node instanceof Ternary
                || node instanceof Elvis) {
            // Opérateurs autorisés : comparaisons, logique, ternaire, Elvis.
        } else if (node instanceof OperatorMatches) {
            throw refused("opérateur 'matches' interdit (risque d'expression régulière "
                    + "catastrophique)");
        } else if (node instanceof TypeReference) {
            throw refused("référence de type T(...) interdite (exécution de code)");
        } else if (node instanceof ConstructorReference) {
            throw refused("instanciation avec 'new' interdite");
        } else if (node instanceof MethodReference) {
            throw refused("appel de méthode interdit — conditions en lecture seule");
        } else if (node instanceof Assign) {
            throw refused("affectation interdite — conditions en lecture seule");
        } else if (node instanceof BeanReference || node instanceof FunctionReference) {
            throw refused("référence '" + node.toStringAST() + "' interdite");
        } else if (node instanceof Identifier) {
            throw refused("identifiant '" + node.toStringAST()
                    + "' inconnu — utilisez #variables['cle']");
        } else {
            throw refused("construction '" + node.getClass().getSimpleName() + "' non autorisée");
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            inspect(node.getChild(i), declaredVariables);
        }
    }

    /**
     * Contrôle la clé de premier niveau d'une chaîne d'accès {@code #variables['cle']...}.
     * Dans une chaîne, chaque maillon {@code Indexer} ne porte que sa clé (la cible est
     * implicite) : seul le premier maillon désigne une variable de dossier, la suite
     * navigue dans des sous-objets et n'est pas contrôlée.
     */
    private static void checkCompoundChain(SpelNode node, Set<String> declaredVariables) {
        if (declaredVariables == null || node.getChildCount() < 2) {
            return;
        }
        SpelNode first = node.getChild(0);
        if (!(first instanceof VariableReference)
                || !CANONICAL_VARIABLE.equals(first.toStringAST())) {
            return;
        }
        String key = linkKey(node.getChild(1));
        if (key != null && !declaredVariables.contains(key)) {
            throw refused(
                    "variable '" + key + "' non déclarée dans le bloc 'variables:' du YAML");
        }
    }

    /** Extrait la clé textuelle d'un maillon de chaîne (index littéral ou propriété). */
    private static String linkKey(SpelNode link) {
        if (link instanceof Indexer) {
            for (int i = 0; i < link.getChildCount(); i++) {
                SpelNode child = link.getChild(i);
                if (child instanceof Literal literal) {
                    Object value = literal.getLiteralValue().getValue();
                    return value instanceof String text ? text : null;
                }
            }
            return null;
        }
        if (link instanceof PropertyOrFieldReference) {
            String text = link.toStringAST();
            if (text.startsWith("?.")) {
                text = text.substring(2);
            } else if (text.startsWith(".")) {
                text = text.substring(1);
            }
            return text.isBlank() ? null : text;
        }
        return null;
    }

    /** Contrôle qu'une clé accédée directement sur #variables est déclarée (recette 1.5). */
    private static void checkDirectKey(SpelNode node, Set<String> declaredVariables) {
        if (declaredVariables == null) {
            return;
        }
        if (node.getChildCount() == 0
                || !(node.getChild(0) instanceof VariableReference)
                || !CANONICAL_VARIABLE.equals(node.getChild(0).toStringAST())) {
            return;
        }
        String key = null;
        if (node instanceof Indexer
                && node.getChildCount() > 1
                && node.getChild(1) instanceof Literal literal) {
            Object value = literal.getLiteralValue().getValue();
            if (value instanceof String text) {
                key = text;
            }
        } else if (node instanceof PropertyOrFieldReference) {
            String text = node.toStringAST();
            if (text.startsWith("?.")) {
                text = text.substring(2);
            } else if (text.startsWith(".")) {
                text = text.substring(1);
            }
            key = text;
        }
        if (key != null && !declaredVariables.contains(key)) {
            throw refused(
                    "variable '" + key + "' non déclarée dans le bloc 'variables:' du YAML");
        }
    }

    private static WorkflowValidationException refused(String reason) {
        return new WorkflowValidationException("Condition SpEL refusée : " + reason + ".");
    }

    private static boolean matchesWordAt(String text, int index, String word) {
        return text.regionMatches(index, word, 0, word.length());
    }

    private static boolean isWordCharBefore(String text, int index) {
        if (index <= 0) {
            return false;
        }
        char before = text.charAt(index - 1);
        return Character.isLetterOrDigit(before) || before == '_' || before == '$'
                || before == '#' || before == '.';
    }

    private static boolean isWordCharAfter(String text, int index) {
        if (index >= text.length()) {
            return false;
        }
        char after = text.charAt(index);
        return Character.isLetterOrDigit(after) || after == '_' || after == '$';
    }

    private static int maxNestingDepth(String text) {
        boolean inSingle = false;
        boolean inDouble = false;
        int depth = 0;
        int max = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\'' && !inDouble) {
                inSingle = !inSingle;
            } else if (c == '"' && !inSingle) {
                inDouble = !inDouble;
            } else if (!inSingle && !inDouble && (c == '(' || c == '[')) {
                depth++;
                max = Math.max(max, depth);
            } else if (!inSingle && !inDouble && (c == ')' || c == ']')) {
                depth = Math.max(0, depth - 1);
            }
        }
        return max;
    }
}
