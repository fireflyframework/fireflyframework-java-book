package com.firefly.lumen.core.mapper;

import com.firefly.lumen.core.domain.LoanApplication;
import com.firefly.lumen.core.dto.CreateLoanApplicationRequest;
import com.firefly.lumen.core.dto.LoanApplicationResponse;
import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

/**
 * Maps between {@link LoanApplication} entities and the REST DTOs.
 *
 * <p>Uses MapStruct with the Spring component model — the same approach as the
 * firefly-oss {@code *Mapper} interfaces — so the generated implementation is a
 * Spring bean that can be injected into the service. The entity-construction
 * side is hand-written ({@link #toNewEntity}) because creating an application
 * applies domain defaults (a fresh status and public number) rather than a
 * field-for-field copy.
 */
@Mapper(
        componentModel = MappingConstants.ComponentModel.SPRING,
        unmappedTargetPolicy = ReportingPolicy.IGNORE
)
public interface LoanApplicationMapper {

    /**
     * Projects a persisted aggregate to its API response shape.
     *
     * @param entity the persisted application
     * @return the response DTO
     */
    LoanApplicationResponse toResponse(LoanApplication entity);

    /**
     * Builds a new, unsaved {@link LoanApplication} from a create request,
     * normalising the currency and starting the aggregate in
     * {@link com.firefly.lumen.core.domain.ApplicationStatus#DRAFT}. The
     * identifiers and timestamps are assigned by the service before persisting.
     *
     * @param request the validated create request
     * @return a transient entity ready to be submitted and saved
     */
    default LoanApplication toNewEntity(CreateLoanApplicationRequest request) {
        return LoanApplication.builder()
                .applicantId(request.applicantId())
                .requestedAmount(request.requestedAmount())
                .currency(request.currency() == null ? null : request.currency().toUpperCase())
                .termMonths(request.termMonths())
                .purpose(request.purpose())
                .build();
    }
}
