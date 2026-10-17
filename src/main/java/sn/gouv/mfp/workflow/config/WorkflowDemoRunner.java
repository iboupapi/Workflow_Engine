package sn.gouv.mfp.workflow.config;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import sn.gouv.mfp.workflow.domain.definition.StepType;
import sn.gouv.mfp.workflow.domain.definition.WorkflowDefinition;
import sn.gouv.mfp.workflow.domain.definition.WorkflowStep;
import sn.gouv.mfp.workflow.domain.definition.WorkflowTransition;
import sn.gouv.mfp.workflow.domain.execution.InstanceStatus;
import sn.gouv.mfp.workflow.domain.execution.WorkflowInstance;
import sn.gouv.mfp.workflow.repository.WorkflowDefinitionRepository;
import sn.gouv.mfp.workflow.repository.WorkflowInstanceRepository;
import sn.gouv.mfp.workflow.repository.WorkflowStepRepository;
import sn.gouv.mfp.workflow.repository.WorkflowTransitionRepository;

/**
 * Étape 1 — preuve de fonctionnement du socle de données.
 * <p>
 * Au démarrage, garantit l'existence d'un circuit de démonstration {@code GIRAFE_ACTES v1}
 * (S_VISA -&gt; DGFP), y fait circuler un dossier fictif portant des variables JSONB
 * (nature de l'acte, numéro officiel, circuit), puis le relit pour prouver le round-trip JSONB.
 * <p>
 * Idempotent : ne recrée rien si la définition existe déjà. Désactivable avec le profil {@code nodemo}.
 */
@Component
@Profile("demo")
public class WorkflowDemoRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(WorkflowDemoRunner.class);

    private final WorkflowDefinitionRepository definitions;
    private final WorkflowStepRepository steps;
    private final WorkflowTransitionRepository transitions;
    private final WorkflowInstanceRepository instances;

    public WorkflowDemoRunner(
            WorkflowDefinitionRepository definitions,
            WorkflowStepRepository steps,
            WorkflowTransitionRepository transitions,
            WorkflowInstanceRepository instances) {
        this.definitions = definitions;
        this.steps = steps;
        this.transitions = transitions;
        this.instances = instances;
    }

    @Override
    @Transactional
    public void run(String... args) {
        WorkflowDefinition definition = definitions
                .findByKeyAndVersion("GIRAFE_ACTES", 1)
                .orElseGet(this::createDemoCircuit);

        Map<String, Object> variables = new HashMap<>();
        variables.put("natureActe", "ARRETE");
        variables.put("numeroOfficiel", "2026-00123/MFP");
        variables.put("circuit", "COURT");
        variables.put("demandeur", Map.of("matricule", "SN-042", "nom", "Agent Démo"));
        variables.put("montantIndemnite", 150000);

        WorkflowInstance instance = new WorkflowInstance();
        instance.setDefinition(definition);
        instance.setCurrentStepKey("S_VISA");
        instance.setStatus(InstanceStatus.ACTIVE);
        instance.setVariables(variables);
        instance = instances.save(instance);

        WorkflowInstance reloaded =
                instances.findById(instance.getId()).orElseThrow();
        log.info(
                "Etape 1 OK — instance {} sur l'étape '{}' avec variables JSONB relues : {}",
                reloaded.getId(),
                reloaded.getCurrentStepKey(),
                reloaded.getVariables());
    }

    private WorkflowDefinition createDemoCircuit() {
        WorkflowDefinition definition = new WorkflowDefinition();
        definition.setKey("GIRAFE_ACTES");
        definition.setVersion(1);
        definition.setName("Circuit des actes GIRAFE (démo)");
        definition = definitions.save(definition);

        WorkflowStep visa = new WorkflowStep();
        visa.setDefinition(definition);
        visa.setStepKey("S_VISA");
        visa.setType(StepType.START);
        // Rôle responsable de l'étape : gouverne l'accès aux actions (/start) et la boîte
        // de réception. Le circuit de démonstration est piloté par le rôle AGENT_BUREAU.
        visa.setOwners(List.of("AGENT_BUREAU"));
        visa = steps.save(visa);

        WorkflowStep dgfp = new WorkflowStep();
        dgfp.setDefinition(definition);
        dgfp.setStepKey("DGFP");
        dgfp.setType(StepType.END);
        steps.save(dgfp);

        WorkflowTransition valider = new WorkflowTransition();
        valider.setSourceStep(visa);
        valider.setTargetStepKey("DGFP");
        valider.setAction("VALIDER");
        valider.setConditionExpression("#variables['circuit'] == 'COURT'");
        transitions.save(valider);

        WorkflowTransition rejeter = new WorkflowTransition();
        rejeter.setSourceStep(visa);
        rejeter.setTargetStepKey("S_VISA");
        rejeter.setAction("REJETER");
        rejeter.setConditionExpression(null); // inconditionnelle
        transitions.save(rejeter);

        log.info("Circuit de démonstration GIRAFE_ACTES v1 créé (S_VISA -> DGFP).");
        return definition;
    }
}
