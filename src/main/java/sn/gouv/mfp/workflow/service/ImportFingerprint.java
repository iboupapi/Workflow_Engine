package sn.gouv.mfp.workflow.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import sn.gouv.mfp.workflow.service.dto.WorkflowConfigDTO;
import sn.gouv.mfp.workflow.service.dto.WorkflowConfigDTO.StepConfigDTO;
import sn.gouv.mfp.workflow.service.dto.WorkflowConfigDTO.TransitionConfigDTO;

/**
 * Empreinte du contenu analysé d'une définition (cahier de recette 7.6 / 7.7).
 * <p>
 * L'empreinte porte sur le modèle canonique : conditions SpEL <strong>normalisées</strong>
 * (ainsi {@code variables['x']} et {@code #variables['x']} donnent la même empreinte),
 * listes triées (l'ordre des blocs n'influe pas), commentaires et indentation ignorés
 * (ils ne font pas partie du modèle). Le champ {@code version} du YAML est
 * <strong>exclu</strong> : purement informatif, il ne déclenche jamais de nouvelle version.
 */
public final class ImportFingerprint {

    private static final ObjectMapper JSON = new ObjectMapper();

    private ImportFingerprint() {
    }

    /**
     * Calcule l'empreinte SHA-256 hexadécimale (64 caractères) du contenu canonique.
     *
     * @param config              configuration analysée
     * @param canonicalConditions conditions normalisées par clé de transition
     */
    public static String sha256Hex(
            WorkflowConfigDTO config, Map<TransitionConfigDTO, String> canonicalConditions) {
        try {
            Map<String, Object> model = new TreeMap<>();
            model.put("definition", Map.of(
                    "key", orEmpty(config.definition().key()),
                    "name", orEmpty(config.definition().name())));
            List<String> entryPoints = config.entryPoints() == null
                    ? List.of()
                    : config.entryPoints().stream()
                            .map(e -> orEmpty(e.role()) + "->" + orEmpty(e.startStepKey())
                                    + (e.condition() != null ? "[" + e.condition().trim() + "]" : ""))
                            .sorted()
                            .toList();
            model.put("entryPoints", entryPoints);
            Map<String, Object> settings = new TreeMap<>();
            if (config.settings() != null) {
                if (config.settings().maxActiveInstancesPerInitiator() != null) {
                    settings.put("maxActiveInstancesPerInitiator",
                            config.settings().maxActiveInstancesPerInitiator());
                }
                if (config.settings().maxActiveInstancesPerType() != null) {
                    settings.put("maxActiveInstancesPerType",
                            config.settings().maxActiveInstancesPerType());
                }
                if (config.settings().resumeStrategyDefault() != null) {
                    settings.put("resumeStrategyDefault",
                            config.settings().resumeStrategyDefault());
                }
                if (config.settings().activeInstanceLimits() != null) {
                    List<String> limits = config.settings().activeInstanceLimits().stream()
                            .map(limit -> orEmpty(limit.role())
                                    + ":" + (limit.maxPerType() == null ? "-" : limit.maxPerType())
                                    + ":" + (limit.maxPerInitiator() == null
                                            ? "-" : limit.maxPerInitiator()))
                            .sorted()
                            .toList();
                    settings.put("activeInstanceLimits", limits);
                }
            }
            model.put("settings", settings);
            List<Map<String, Object>> steps = config.steps().stream()
                    .sorted(Comparator.comparing(StepConfigDTO::key, Comparator.nullsFirst(String::compareTo)))
                    .map(step -> {
                        Map<String, Object> entry = new TreeMap<>();
                        entry.put("key", orEmpty(step.key()));
                        entry.put("name", orEmpty(step.name()));
                        entry.put("type", orEmpty(step.type()));
                        List<String> required = step.requiredDataOnValidate() == null
                                ? List.of()
                                : new ArrayList<>(step.requiredDataOnValidate()).stream()
                                        .sorted()
                                        .toList();
                        entry.put("requiredData", required);
                        List<String> owners = step.owners() == null
                                ? List.of()
                                : new ArrayList<>(step.owners()).stream().sorted().toList();
                        entry.put("owners", owners);
                        return entry;
                    })
                    .toList();
            model.put("steps", steps);
            List<Map<String, Object>> transitions = config.transitions().stream()
                    .sorted(Comparator.comparing(TransitionConfigDTO::source,
                                    Comparator.nullsFirst(String::compareTo))
                            .thenComparing(TransitionConfigDTO::action,
                                    Comparator.nullsFirst(String::compareTo))
                            .thenComparing(TransitionConfigDTO::target,
                                    Comparator.nullsFirst(String::compareTo)))
                    .map(transition -> {
                        Map<String, Object> entry = new TreeMap<>();
                        entry.put("source", orEmpty(transition.source()));
                        entry.put("action", orEmpty(transition.action()));
                        entry.put("target", orEmpty(transition.target()));
                        entry.put("condition", canonicalConditions.get(transition));
                        entry.put("setsHandler", Boolean.TRUE.equals(transition.setsHandler()));
                        return entry;
                    })
                    .toList();
            model.put("transitions", transitions);
            // MOT-10 : la déclaration (clé, type, obligatoire) fait partie du contenu canonique —
            // un changement de type ou de caractère obligatoire crée une nouvelle version.
            List<String> variables = config.variables() == null
                    ? List.of()
                    : config.variables().stream()
                            .map(v -> orEmpty(v.key())
                                    + ":" + (v.type() == null || v.type().isBlank()
                                            ? "string" : v.type().trim().toLowerCase())
                                    + ":" + (Boolean.TRUE.equals(v.required())
                                            ? "required" : "optional"))
                            .sorted()
                            .toList();
            model.put("variables", variables);
            String json = JSON.writeValueAsString(model);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(json.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Empreinte du contenu impossible.", e);
        }
    }

    private static String orEmpty(String value) {
        return value != null ? value : "";
    }
}
