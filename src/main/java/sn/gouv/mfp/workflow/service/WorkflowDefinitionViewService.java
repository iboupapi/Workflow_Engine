package sn.gouv.mfp.workflow.service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sn.gouv.mfp.workflow.domain.definition.EntryPoint;
import sn.gouv.mfp.workflow.domain.definition.StepType;
import sn.gouv.mfp.workflow.domain.definition.VariableDeclaration;
import sn.gouv.mfp.workflow.domain.definition.WorkflowDefinition;
import sn.gouv.mfp.workflow.domain.definition.WorkflowStep;
import sn.gouv.mfp.workflow.domain.definition.WorkflowTransition;
import sn.gouv.mfp.workflow.exception.WorkflowNotFoundException;
import sn.gouv.mfp.workflow.repository.WorkflowDefinitionRepository;
import sn.gouv.mfp.workflow.repository.WorkflowStepRepository;
import sn.gouv.mfp.workflow.repository.WorkflowTransitionRepository;

/**
 * Lecture STRUCTURELLE d'un circuit (Phase 2) : expose la carte statique d'une définition
 * (étapes, rôles responsables, transitions, points d'entrée, variables) pour le visualiseur
 * de la console. Lecture seule, aucune donnée de dossier.
 * <p>
 * Résout la <strong>dernière version</strong> de la clé, comme l'exécution : la carte
 * affichée correspond exactement au circuit sur lequel les nouveaux dossiers démarrent.
 */
@Service
@Transactional(readOnly = true)
public class WorkflowDefinitionViewService {

    private final WorkflowDefinitionRepository definitions;
    private final WorkflowStepRepository steps;
    private final WorkflowTransitionRepository transitions;

    public WorkflowDefinitionViewService(
            WorkflowDefinitionRepository definitions,
            WorkflowStepRepository steps,
            WorkflowTransitionRepository transitions) {
        this.definitions = definitions;
        this.steps = steps;
        this.transitions = transitions;
    }

    /** Carte statique de la dernière version du circuit, 404 si la clé est inconnue. */
    public DefinitionView view(String definitionKey) {
        WorkflowDefinition definition = definitions.findByKey(definitionKey).stream()
                .max(Comparator.comparing(WorkflowDefinition::getVersion))
                .orElseThrow(() -> new WorkflowNotFoundException(
                        "Aucune définition de workflow pour la clé '" + definitionKey + "'"));
        List<StepView> stepViews = steps.findByDefinitionId(definition.getId()).stream()
                .map(step -> new StepView(
                        step.getStepKey(),
                        step.getName(),
                        step.getType(),
                        step.getOwners() != null ? step.getOwners() : List.of(),
                        step.getRequiredData() != null ? step.getRequiredData() : List.of()))
                .sorted(Comparator.comparing(StepView::key))
                .toList();
        List<TransitionView> transitionViews =
                transitions.findBySourceStepDefinitionId(definition.getId()).stream()
                        .map(transition -> new TransitionView(
                                transition.getSourceStep().getStepKey(),
                                transition.getAction(),
                                transition.getTargetStepKey(),
                                transition.getConditionExpression(),
                                transition.isSetsHandler()))
                        .sorted(Comparator.comparing(TransitionView::source)
                                .thenComparing(TransitionView::action)
                                .thenComparing(TransitionView::target))
                        .toList();
        return new DefinitionView(
                definition.getKey(),
                definition.getVersion(),
                definition.getName(),
                definition.getSettings(),
                definition.getEntryPoints() != null ? definition.getEntryPoints() : List.of(),
                definition.getVariableDeclarations() != null
                        ? definition.getVariableDeclarations()
                        : List.of(),
                stepViews,
                transitionViews);
    }

    /** Carte statique d'un circuit (structure exposée au visualiseur). */
    public record DefinitionView(
            String key,
            Integer version,
            String name,
            Map<String, Object> settings,
            List<EntryPoint> entryPoints,
            List<VariableDeclaration> variables,
            List<StepView> steps,
            List<TransitionView> transitions) {
    }

    /** Étape exposée : nature, rôles responsables et données obligatoires. */
    public record StepView(
            String key,
            String name,
            StepType type,
            List<String> owners,
            List<String> requiredData) {
    }

    /** Transition exposée : condition SpEL éventuelle et marqueur de traitement. */
    public record TransitionView(
            String source,
            String action,
            String target,
            String condition,
            boolean setsHandler) {
    }
}
