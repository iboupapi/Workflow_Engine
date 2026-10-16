package sn.gouv.mfp.workflow.service.dto;

import sn.gouv.mfp.workflow.domain.definition.WorkflowDefinition;

/**
 * Résultat d'un import YAML : la définition (nouvelle version créée, ou version existante
 * réutilisée en cas de réimport à l'identique) et l'indicateur de création, qui détermine
 * le statut HTTP (201 créé, 200 déjà à jour).
 */
public record DefinitionImport(WorkflowDefinition definition, boolean created) {
}
