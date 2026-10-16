package sn.gouv.mfp.workflow.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.yaml.snakeyaml.LoaderOptions;

/**
 * Fournit l'{@code ObjectMapper} capable de désérialiser le YAML de configuration des circuits.
 * <p>
 * Durcissement SnakeYAML (cahier de recette 7.4) : constructeur sûr par défaut en 2.x
 * (les balises {@code !!java...} sont rejetées), taille plafonnée à ~1 Mo, alias limités
 * (bombes récursives rejetées) et clés dupliquées refusées.
 */
@Configuration
public class YamlConfig {

    /** Taille maximale acceptée d'un fichier de configuration (recette 7.4). */
    public static final int MAX_YAML_BYTES = 1_000_000;

    @Bean("yamlObjectMapper")
    public ObjectMapper yamlObjectMapper() {
        LoaderOptions loaderOptions = new LoaderOptions();
        loaderOptions.setCodePointLimit(MAX_YAML_BYTES);
        loaderOptions.setMaxAliasesForCollections(50);
        loaderOptions.setAllowDuplicateKeys(false);
        YAMLFactory factory = YAMLFactory.builder().loaderOptions(loaderOptions).build();
        return new ObjectMapper(factory)
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }
}
