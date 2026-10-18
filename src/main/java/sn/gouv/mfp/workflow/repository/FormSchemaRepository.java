package sn.gouv.mfp.workflow.repository;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import sn.gouv.mfp.workflow.domain.request.FormSchema;

public interface FormSchemaRepository extends JpaRepository<FormSchema, UUID> {
}
