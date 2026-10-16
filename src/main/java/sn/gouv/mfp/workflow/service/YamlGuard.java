package sn.gouv.mfp.workflow.service;

import java.io.StringReader;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.composer.ComposerException;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.SequenceNode;
import org.yaml.snakeyaml.nodes.Tag;
import sn.gouv.mfp.workflow.exception.WorkflowValidationException;

/**
 * Garde-fou structurel du YAML de configuration (cahier de recette 7.4).
 * <p>
 * Analyse l'arbre du document avec le parseur SnakeYAML lui-même (pas d'expression
 * régulière contournable) : toute balise non standard (ex. {@code !!javax...},
 * {@code !perso}, {@code !<uri>}, y compris sur les clés) et toute ancre ou alias
 * {@code &...} / {@code *...} est refusée explicitement. Aucun objet n'est instancié
 * pendant ce contrôle (simple composition de l'arbre).
 * <p>
 * Un {@code !} ou {@code !!} à l'intérieur d'un littéral entre quotes reste un scalaire
 * ordinaire et n'est jamais confondu avec une balise.
 */
public final class YamlGuard {

    private static final String STANDARD_TAG_PREFIX = "tag:yaml.org,2002:";

    private YamlGuard() {
    }

    /** Compose l'arbre YAML et refuse balises non standard, ancres et alias. */
    public static void checkStructure(String yamlContent) {
        LoaderOptions options = new LoaderOptions();
        options.setMaxAliasesForCollections(50);
        Node root;
        try {
            root = new Yaml(new SafeConstructor(options)).compose(new StringReader(yamlContent));
        } catch (ComposerException e) {
            throw new WorkflowValidationException(
                    "YAML refusé (structure récursive ou invalide) : " + e.getMessage());
        } catch (YAMLException e) {
            throw new WorkflowValidationException("YAML illisible : " + e.getMessage());
        }
        if (root == null) {
            throw new WorkflowValidationException("Contenu YAML vide : import impossible.");
        }
        walk(root);
    }

    private static void walk(Node node) {
        if (node.getAnchor() != null) {
            throw new WorkflowValidationException("Ancre ou alias YAML '" + node.getAnchor()
                    + "' interdit : les références internes sont refusées.");
        }
        Tag tag = node.getTag();
        if (tag != null
                && tag.getValue() != null
                && !tag.getValue().startsWith(STANDARD_TAG_PREFIX)) {
            throw new WorkflowValidationException("Balise YAML '" + tag.getValue()
                    + "' interdite : la désérialisation d'objets est refusée.");
        }
        if (node instanceof MappingNode mapping) {
            for (NodeTuple tuple : mapping.getValue()) {
                walk(tuple.getKeyNode());
                walk(tuple.getValueNode());
            }
        } else if (node instanceof SequenceNode sequence) {
            for (Node child : sequence.getValue()) {
                walk(child);
            }
        }
    }
}
