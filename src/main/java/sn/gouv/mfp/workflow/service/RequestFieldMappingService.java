package sn.gouv.mfp.workflow.service;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * REQ-02 — liaison des champs du formulaire aux variables de décision du circuit.
 * <p>
 * La carte {@code field_mapping} d'une version publiée associe un champ de saisie à une
 * variable moteur : {@code {"typeActe": "natureActe"}} fait de la valeur saisie sous
 * {@code typeActe} la variable {@code natureActe} du dossier ({@code #variables['natureActe']}),
 * celle que lisent les conditions SpEL et les plafonds (MOT-05).
 * <p>
 * Règles, volontairement restrictives :
 * <ul>
 *   <li>seuls les champs <strong>déclarés</strong> dans la liaison sont transmis : le
 *       formulaire complet n'alimente jamais les variables du dossier ;</li>
 *   <li>un champ absent ou de valeur {@code null} est <strong>omis</strong> plutôt que
 *       transmis à {@code null} : une variable obligatoire du circuit reste ainsi
 *       réellement manquante et le refus vient de MOT-10, avec un message juste ;</li>
 *   <li>les valeurs sont transmises telles quelles (aucune coercition : listes, nombres,
 *       booléens et objets arrivent dans leur type JSON) ;</li>
 *   <li>une cible vide ou une variable alimentée par deux champs est une erreur de
 *       configuration, pas un cas d'exécution : la soumission est refusée.</li>
 * </ul>
 */
@Service
public class RequestFieldMappingService {

    private static final Logger log = LoggerFactory.getLogger(RequestFieldMappingService.class);

    /**
     * Construit les variables de décision à partir des données saisies.
     *
     * @param fieldMapping carte champ de formulaire → variable moteur (peut être nulle : aucune
     *                     variable n'est alors transmise)
     * @param formData     données saisies par l'usager
     * @return variables du dossier, dans l'ordre de la liaison
     * @throws IllegalStateException liaison incohérente (cible absente ou variable ambiguë)
     */
    public Map<String, Object> toWorkflowVariables(
            Map<String, Object> fieldMapping, Map<String, Object> formData) {
        Map<String, Object> variables = new LinkedHashMap<>();
        if (fieldMapping == null || fieldMapping.isEmpty() || formData == null || formData.isEmpty()) {
            return variables;
        }

        Set<String> boundTargets = new HashSet<>();
        for (Map.Entry<String, Object> binding : fieldMapping.entrySet()) {
            String target = requireTarget(binding);
            if (!boundTargets.add(target)) {
                throw new IllegalStateException(
                        "Liaison de champ ambiguë (REQ-02) : la variable '" + target
                                + "' est alimentée par plusieurs champs du formulaire.");
            }
            Object value = formData.get(binding.getKey());
            if (value != null) {
                variables.put(target, value);
            }
        }

        if (log.isDebugEnabled()) {
            log.debug("REQ-02 : {} champ(s) lié(s) aux variables {} sur {} champ(s) saisi(s)",
                    variables.size(), variables.keySet(), formData.size());
        }
        return variables;
    }

    private static String requireTarget(Map.Entry<String, Object> binding) {
        Object target = binding.getValue();
        if (!(target instanceof String name) || name.isBlank()) {
            throw new IllegalStateException(
                    "Liaison de champ invalide (REQ-02) : le champ '" + binding.getKey()
                            + "' ne désigne aucune variable moteur.");
        }
        return name;
    }
}
