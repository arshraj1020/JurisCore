package com.juriscore.casemanagement.domain;

/**
 * What a listing is for.
 *
 * <p>Same reasoning as {@link CourtType}: small, generic, and widened by migration when
 * a real taxonomy arrives. Mirrored by {@code ck_hearings_type}.
 */
public enum HearingType {
    MENTION,
    EVIDENCE,
    ARGUMENTS,
    JUDGMENT,
    OTHER
}
