package com.juriscore.casework.api.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * @param lead ask for this lawyer to take the lead. Ignored when they are the first
 *             assignee, who becomes lead regardless — a staffed case always has one.
 */
public record AssignLawyerRequest(@NotNull UUID lawyerUserId, Boolean lead) {

    public boolean leadRequested() {
        return Boolean.TRUE.equals(lead);
    }
}
