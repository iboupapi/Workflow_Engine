package sn.gouv.mfp.workflow.service.attachment;

import java.nio.file.Path;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Branchement des pièces jointes (FRM-05).
 * <p>
 * Le stockage par défaut écrit sur le système de fichiers local
 * ({@code workflow.attachments.directory}) : c'est la mise en œuvre de développement. Le
 * passage à un stockage objet (S3/MinIO) consiste à fournir un autre bean
 * {@link AttachmentStorage}, sans toucher au service ni au schéma.
 */
@Configuration
@EnableConfigurationProperties(AttachmentProperties.class)
public class AttachmentConfig {

    @Bean
    AttachmentStorage attachmentStorage(AttachmentProperties properties) {
        return new LocalFileAttachmentStorage(Path.of(properties.directory()));
    }

    /** Analyse antivirus de recette : à remplacer par le moteur ministériel (FRM-05). */
    @Bean
    AntivirusScanner antivirusScanner() {
        return new StubAntivirusScanner();
    }
}
