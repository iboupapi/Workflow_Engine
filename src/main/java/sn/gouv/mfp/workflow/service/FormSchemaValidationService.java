package sn.gouv.mfp.workflow.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import sn.gouv.mfp.workflow.domain.request.FormSchema;
import sn.gouv.mfp.workflow.exception.FormSchemaValidationException;

/**
 * Validation côté serveur des données de formulaire (FRM-03) contre le JSON Schema 2020-12
 * publié du type de demande (FRM-01).
 * <p>
 * Le contrôle est <strong>systématique et fermé</strong> : il n'existe aucun chemin qui
 * laisserait passer des données non validées. Un schéma absent, vide ou illisible fait
 * échouer la soumission au lieu de l'accepter par défaut.
 * <p>
 * Le schéma est fourni par la version publiée du type de demande
 * ({@code request_type_version}/{@code form_schema}), donc immuable : la compilation est
 * refaite à chaque appel. Un cache par identifiant de schéma pourra être ajouté lorsque
 * l'endpoint de soumission existera et que le volume le justifiera.
 * <p>
 * Les messages d'écart proviennent de la bibliothèque de validation et sont en anglais
 * (la localisation FRM-06 des messages de formulaire reste à traiter).
 */
@Service
public class FormSchemaValidationService {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Fabrique du dialecte 2020-12, seule version acceptée pour les formulaires (FRM-01). */
    private static final JsonSchemaFactory FACTORY =
            JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);

    private static final SchemaValidatorsConfig STRICT_CONFIG = strictConfig();

    private static SchemaValidatorsConfig strictConfig() {
        SchemaValidatorsConfig config = new SchemaValidatorsConfig();
        // FRM-03 « formats » : en 2020-12 l'assertion de format est facultative ; sans cette
        // activation, une date mal formée ou un courriel invalide passeraient la validation.
        config.setFormatAssertionsEnabled(true);
        // Types stricts (FRM-01) : aucun élargissement implicite — « 30 » n'est pas un entier,
        // 18 n'est pas un texte. Sans cela, le contrôle ne serait pas fidèle au schéma.
        config.setTypeLoose(false);
        return config;
    }

    /**
     * Valide les données saisies et lève une exception détaillée si elles ne sont pas conformes.
     *
     * @param formSchema schéma publié du formulaire (obligatoire)
     * @param formData   données saisies ; {@code null} est traité comme un formulaire vide
     * @throws FormSchemaValidationException données non conformes (HTTP 400, écarts inclus)
     * @throws IllegalStateException         schéma absent ou invalide : défaut de configuration,
     *                                       jamais un motif d'acceptation des données
     */
    public void validate(FormSchema formSchema, Map<String, Object> formData) {
        List<String> violations = validateAndCollect(formSchema, formData);
        if (!violations.isEmpty()) {
            throw new FormSchemaValidationException(
                    "Formulaire non conforme au schéma (" + violations.size() + " écart(s)) : "
                            + String.join(" ; ", violations),
                    violations);
        }
    }

    /**
     * Variante non bloquante : renvoie les écarts constatés, liste vide si les données sont
     * conformes. Utile pour un rendu d'erreurs champ par champ côté client.
     */
    public List<String> validateAndCollect(FormSchema formSchema, Map<String, Object> formData) {
        JsonSchema schema = compile(requireSchema(formSchema), formSchema);
        JsonNode dataNode = JSON.valueToTree(formData == null ? Map.of() : formData);
        Set<ValidationMessage> errors = schema.validate(dataNode);
        return errors.stream()
                .map(FormSchemaValidationService::describe)
                .sorted()
                .toList();
    }

    private JsonNode requireSchema(FormSchema formSchema) {
        if (formSchema == null || formSchema.getJsonSchema() == null
                || formSchema.getJsonSchema().isEmpty()) {
            throw new IllegalStateException(
                    "Aucun schéma de formulaire : la soumission ne peut pas être validée (aucune "
                            + "donnée n'est acceptée sans contrôle).");
        }
        return JSON.valueToTree(formSchema.getJsonSchema());
    }

    private JsonSchema compile(JsonNode schemaNode, FormSchema formSchema) {
        try {
            return FACTORY.getSchema(schemaNode, STRICT_CONFIG);
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                    "JSON Schema invalide pour le formulaire "
                            + (formSchema.getId() != null ? formSchema.getId() : "(non persisté)")
                            + " : la soumission est refusée.",
                    e);
        }
    }

    private static String describe(ValidationMessage message) {
        return message.getInstanceLocation() + " : " + message.getMessage();
    }
}
