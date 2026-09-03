package com.juriscore.casemanagement.api.dto;

import com.juriscore.casemanagement.domain.CourtType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Used for both creating and editing a court: the fields are the same, and two records
 * differing only in a version field would be a copy nobody keeps in step.
 *
 * @param version required on update, ignored on create. See the schema note.
 */
public record CourtRequest(
        @NotBlank @Size(max = 200) String name,
        @NotNull CourtType courtType,
        @Size(max = 255) String addressLine1,
        @Size(max = 255) String addressLine2,
        @Size(max = 120) String city,
        @Size(max = 120) String state,
        @Size(max = 120) String country,
        @Size(max = 64) String timezone,
        @Schema(description = "The version you last read from CourtResponse.version. "
                + "Required on update; a stale value answers 409 CONCURRENT_MODIFICATION. "
                + "Ignored when creating.")
        Long version) {
}
