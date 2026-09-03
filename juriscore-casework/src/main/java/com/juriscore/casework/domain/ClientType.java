package com.juriscore.casework.domain;

/**
 * What kind of party the client is.
 *
 * <p>Two values, because two is what the field is for: an individual and a company
 * differ in how they are addressed and billed, and nothing in Phase 2 needs a finer
 * distinction. Adding values later is a migration that widens one check constraint.
 */
public enum ClientType {
    INDIVIDUAL,
    CORPORATE
}
