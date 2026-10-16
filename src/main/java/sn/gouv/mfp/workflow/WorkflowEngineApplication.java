package sn.gouv.mfp.workflow;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Contact;
import io.swagger.v3.oas.annotations.info.Info;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Moteur de workflow unique du Ministère de la Fonction Publique.
 * <p>
 * Documentation interactive de l'API : {@code /swagger-ui.html} (spécification OpenAPI :
 * {@code /v3/api-docs}).
 */
@OpenAPIDefinition(
        info = @Info(
                title = "Moteur de Workflow — API",
                version = "1.0",
                description = "Moteur générique de circuits de validation : initiation des dossiers, "
                        + "transitions conditionnelles (SpEL), gardes métier et import à chaud "
                        + "des définitions YAML.",
                contact = @Contact(name = "Ministère de la Fonction Publique")))
@SpringBootApplication
public class WorkflowEngineApplication {

	public static void main(String[] args) {
		SpringApplication.run(WorkflowEngineApplication.class, args);
	}

}
