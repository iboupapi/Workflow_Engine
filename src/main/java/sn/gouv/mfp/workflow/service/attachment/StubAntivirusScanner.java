package sn.gouv.mfp.workflow.service.attachment;

import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sn.gouv.mfp.workflow.domain.request.AttachmentScanStatus;

/**
 * Analyse antivirus de recette (FRM-05), en attendant le branchement du moteur réel.
 * <p>
 * Le ministère n'a pas encore fourni de serveur d'analyse (ClamAV/clamd ou passerelle
 * ministérielle) ; ce composant <strong>ne remplace pas</strong> un antivirus : il rend un
 * verdict déterministe qui permet de vérifier la chaîne complète (analyse
 * <em>avant</em> mise à disposition, refus d'un contenu signalé, verdict consigné).
 * <p>
 * Le fichier de test EICAR, signature universelle des antivirus, est reconnu comme
 * infecté ; tout autre contenu est déclaré sain. Le jour du branchement réel, seule
 * cette classe est remplacée : le service, le schéma et les tests de refus restent valables.
 */
public class StubAntivirusScanner implements AntivirusScanner {

    private static final Logger log = LoggerFactory.getLogger(StubAntivirusScanner.class);

    /** Signature du fichier de test EICAR (standard de facto des antivirus). */
    static final String EICAR_SIGNATURE = "EICAR-STANDARD-ANTIVIRUS-TEST-FILE";

    @Override
    public AttachmentScanStatus scan(String filename, byte[] content) {
        if (content != null
                && new String(content, StandardCharsets.ISO_8859_1).contains(EICAR_SIGNATURE)) {
            log.warn("Analyse antivirus : contenu signalé (signature de test EICAR) dans '{}'.", filename);
            return AttachmentScanStatus.INFECTED;
        }
        return AttachmentScanStatus.CLEAN;
    }
}
