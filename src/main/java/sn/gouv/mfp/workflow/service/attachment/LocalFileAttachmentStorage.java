package sn.gouv.mfp.workflow.service.attachment;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Stockage des pièces jointes sur le système de fichiers local (développement).
 * <p>
 * La clé rendue est un chemin <strong>relatif</strong> au répertoire racine
 * ({@code <demande>/<identifiant aléatoire><extension>}) : le nom d'origine n'est jamais
 * utilisé comme nom de fichier (pas de traversée de répertoire, pas de collision) et
 * l'extension, réduite à un suffixe alphanumérique court, ne sert qu'à l'ouverture
 * humaine du fichier sur le disque.
 * <p>
 * En production, ce composant est remplacé par un client de stockage objet : l'interface
 * {@link AttachmentStorage} est le seul point de contact du service.
 */
public class LocalFileAttachmentStorage implements AttachmentStorage {

    private static final Logger log = LoggerFactory.getLogger(LocalFileAttachmentStorage.class);

    private static final int MAX_EXTENSION_LENGTH = 10;

    private final Path root;

    public LocalFileAttachmentStorage(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    @Override
    public String store(UUID submissionId, String filename, byte[] content) {
        String key = submissionId + "/" + UUID.randomUUID() + extensionOf(filename);
        try {
            Path target = resolve(key);
            Files.createDirectories(target.getParent());
            Files.write(target, content, StandardOpenOption.CREATE_NEW);
        } catch (IOException e) {
            throw new AttachmentStorageException(
                    "Le dépôt de la pièce jointe a échoué (stockage indisponible).", e);
        }
        log.debug("Pièce jointe déposée sous la clé '{}' ({} octets)", key, content.length);
        return key;
    }

    @Override
    public InputStream load(String storageKey) {
        try {
            return Files.newInputStream(resolve(storageKey));
        } catch (IOException e) {
            throw new AttachmentStorageException(
                    "Le contenu de la pièce jointe '" + storageKey + "' est introuvable.", e);
        }
    }

    @Override
    public void delete(String storageKey) {
        try {
            Files.deleteIfExists(resolve(storageKey));
        } catch (IOException e) {
            log.warn("Suppression impossible du contenu '{}' : {}", storageKey, e.getMessage());
        }
    }

    /** Résolution sûre : la clé résolue doit rester sous la racine (aucune traversée). */
    private Path resolve(String storageKey) {
        Path resolved = root.resolve(storageKey).normalize();
        if (!resolved.startsWith(root)) {
            throw new AttachmentStorageException(
                    "Clé de stockage invalide (hors du répertoire de pièces jointes).");
        }
        return resolved;
    }

    /** Extension préservée si elle est courte et alphanumérique, sinon aucune. */
    private static String extensionOf(String filename) {
        if (filename == null) {
            return "";
        }
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            return "";
        }
        String extension = filename.substring(dot + 1);
        if (extension.length() > MAX_EXTENSION_LENGTH || !extension.matches("[A-Za-z0-9]+")) {
            return "";
        }
        return "." + extension.toLowerCase();
    }
}
