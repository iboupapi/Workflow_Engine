package sn.gouv.mfp.workflow.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import sn.gouv.mfp.workflow.domain.request.RequestType;
import sn.gouv.mfp.workflow.domain.request.RequestTypeStatus;

public interface RequestTypeRepository extends JpaRepository<RequestType, UUID> {

    Optional<RequestType> findByKey(String key);

    boolean existsByKey(String key);

    /** Types de demande proposables à l'usager (référentiel publié). */
    List<RequestType> findByStatusOrderByLabelAsc(RequestTypeStatus status);

    /** Référentiel complet, tous statuts : vue d'administration. */
    List<RequestType> findAllByOrderByLabelAsc();
}
