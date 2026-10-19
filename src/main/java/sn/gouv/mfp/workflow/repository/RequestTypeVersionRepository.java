package sn.gouv.mfp.workflow.repository;

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
}
