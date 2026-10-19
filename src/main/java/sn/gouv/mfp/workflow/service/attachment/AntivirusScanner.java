package sn.gouv.mfp.workflow.service.attachment;

import sn.gouv.mfp.workflow.domain.request.AttachmentScanStatus;

/**
 * Analyse antivirus d'un contenu avant mise à disposition (FRM-05).
 * <p>
 * Le service ne met jamais une pièce jointe à disposition sans verdict favorable : le
 * verdict est rendu <strong>avant</strong> l'enregistrement de la pièce.
 */
public interface AntivirusScanner {

    /**
     * Analyse un contenu et rend son verdict.
     *
     * @param filename nom d'origine (utile aux moteurs qui s'appuient sur l'extension)
     * @param content  contenu à analyser
     * @return {@link AttachmentScanStatus#CLEAN} si le contenu est sain, un verdict de
     *         refus sinon (jamais {@link AttachmentScanStatus#PENDING} : l'analyse est
     *         terminée au retour de cet appel)
     */
    AttachmentScanStatus scan(String filename, byte[] content);
}
