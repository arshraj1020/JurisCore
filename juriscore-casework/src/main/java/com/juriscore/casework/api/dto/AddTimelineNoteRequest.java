package com.juriscore.casework.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** A manual timeline entry. Append-only once written, like every other entry. */
public record AddTimelineNoteRequest(@NotBlank @Size(max = 1000) String summary) {
}
