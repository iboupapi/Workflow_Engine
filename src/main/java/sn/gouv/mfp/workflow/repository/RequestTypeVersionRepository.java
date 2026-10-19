package sn.gouv.mfp.workflow.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import sn.gouv.mfp.workflow.domain.request.RequestTypeStatus;
import sn.gouv.mfp.workflow.domain.request.RequestTypeVersion;

public interface RequestTypeVersionRepository extends JpaRepository<RequestTypeVersion, UUID> {

    /**
     * Version publiée en vigueur d'un type de demande : la plus élevée parmi les versions
     * publiées, à condition que le type soit lui-même PUBLISHED (REQ-01, REQ-03). C'est
     * cette version qui porte le formulaire présenté et le circuit instancié.
     */
    Optional<RequestTypeVersion> findFirstByRequestTypeKeyAndRequestTypeStatusAndPublishedAtIsNotNullOrderByVersionDesc(
            String key, RequestTypeStatus status);

    /**
     * Dernière version d'un type de demande, publiée ou non : sert à numéroter la suivante
     * (les versions sont croissantes par type et immuables, une publication en crée une
     * nouvelle).
     */
    Optional<RequestTypeVersion> findFirstByRequestTypeIdOrderByVersionDesc(UUID requestTypeId);

    /** Historique des versions d'un type de demande, de la plus récente à la plus ancienne. */
    List<RequestTypeVersion> findByRequestTypeIdOrderByVersionDesc(UUID requestTypeId);
}
