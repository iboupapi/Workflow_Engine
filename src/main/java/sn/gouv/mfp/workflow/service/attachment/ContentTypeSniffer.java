package sn.gouv.mfp.workflow.service.attachment;

import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Détection du <strong>type MIME réel</strong> d'un contenu (FRM-05).
 * <p>
 * L'en-tête {@code Content-Type} envoyé par le client n'est qu'une intention : un
 * exécutable peut être annoncé comme PDF. Le type retenu est donc déduit des premiers
 * octets (signatures de fichiers), jamais de la déclaration.
 * <p>
 * Liste <strong>positive</strong> : un contenu non reconnu n'est pas « autorisé par
 * défaut », il est refusé (liste vide = aucun type deviné).
 */
public final class ContentTypeSniffer {

    public static final String PDF = "application/pdf";
    public static final String PNG = "image/png";
    public static final String JPEG = "image/jpeg";
    public static final String GIF = "image/gif";
    public static final String ZIP = "application/zip";
    public static final String TEXT = "text/plain";

    private ContentTypeSniffer() {
    }

    /** Type MIME réel déduit du contenu, vide si le contenu n'est pas reconnu. */
    public static Optional<String> sniff(byte[] content) {
        if (content == null || content.length == 0) {
            return Optional.empty();
        }
        if (startsWith(content, "%PDF-".getBytes(StandardCharsets.US_ASCII))) {
            return Optional.of(PDF);
        }
        if (startsWith(content, new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A})) {
            return Optional.of(PNG);
        }
        if (startsWith(content, new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF})) {
            return Optional.of(JPEG);
        }
        if (startsWith(content, "GIF87a".getBytes(StandardCharsets.US_ASCII))
                || startsWith(content, "GIF89a".getBytes(StandardCharsets.US_ASCII))) {
            return Optional.of(GIF);
        }
        if (startsWith(content, new byte[] {'P', 'K', 0x03, 0x04})) {
            return Optional.of(ZIP);
        }
        if (isUtf8Text(content)) {
            return Optional.of(TEXT);
        }
        return Optional.empty();
    }

    private static boolean startsWith(byte[] content, byte[] signature) {
        if (content.length < signature.length) {
            return false;
        }
        for (int i = 0; i < signature.length; i++) {
            if (content[i] != signature[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * Texte si le contenu est un UTF-8 valide sans octet de contrôle : un binaire
     * arbitraire (sans signature connue) contient presque toujours un octet nul ou une
     * séquence UTF-8 invalide, ce qui le fait refuser plutôt que d'être pris pour du texte.
     */
    private static boolean isUtf8Text(byte[] content) {
        for (byte b : content) {
            if (b == 0) {
                return false;
            }
        }
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(content));
            return true;
        } catch (CharacterCodingException e) {
            return false;
        }
    }
}
