package com.juriscore.documents.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Object keys, and the properties that make traversal and cross-tenant collision
 * unrepresentable rather than merely filtered.
 */
class StorageKeysTest {

    private static final UUID ORG = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID CASE = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID DOC = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Test
    void buildsTheDocumentedShape() {
        assertThat(StorageKeys.forDocument(ORG, CASE, DOC)).isEqualTo(
                "organizations/11111111-1111-1111-1111-111111111111"
                        + "/cases/22222222-2222-2222-2222-222222222222"
                        + "/documents/33333333-3333-3333-3333-333333333333");
    }

    @Test
    @DisplayName("the organization leads, so a bucket policy can scope a role to one firm's prefix")
    void theTenantPrefixComesFirst() {
        assertThat(StorageKeys.forDocument(ORG, CASE, DOC)).startsWith("organizations/" + ORG + "/");
    }

    @Test
    @DisplayName("no caller input reaches the key, so there is nothing to traverse with")
    void theKeyContainsOnlyGeneratedIdentifiers() {
        String key = StorageKeys.forDocument(ORG, CASE, DOC);

        assertThat(key).doesNotContain("..");
        assertThat(key).doesNotContain("//");
        assertThat(key).doesNotStartWith("/");
        // Every segment is either a fixed word or a UUID; a UUID cannot hold a separator.
        for (String segment : key.split("/")) {
            assertThat(segment).isNotEmpty();
            assertThat(segment).matches("organizations|cases|documents|[0-9a-fA-F-]{36}");
        }
    }

    @Test
    @DisplayName("two firms can never collide, because the tenant id is in the path")
    void keysAreDisjointAcrossTenants() {
        UUID otherOrg = UUID.randomUUID();

        assertThat(StorageKeys.forDocument(ORG, CASE, DOC))
                .isNotEqualTo(StorageKeys.forDocument(otherOrg, CASE, DOC));
    }

    @Test
    void everyDocumentGetsItsOwnKey() {
        assertThat(StorageKeys.forDocument(ORG, CASE, DOC))
                .isNotEqualTo(StorageKeys.forDocument(ORG, CASE, UUID.randomUUID()));
    }

    // ------------------------------------------------- reservations and the canonical guard

    @Test
    @DisplayName("a reservation is not a key, and no two registrations can share one")
    void aReservationIsUniqueAndIsNotAKey() {
        String first = StorageKeys.reservation();
        String second = StorageKeys.reservation();

        assertThat(first).isNotEqualTo(second);
        assertThat(StorageKeys.isCanonical(first))
                .as("a reservation must never be mistaken for something storage can be "
                        + "addressed with")
                .isFalse();
    }

    @Test
    void recognisesARealKeyAndNothingElse() {
        assertThat(StorageKeys.isCanonical(StorageKeys.forDocument(ORG, CASE, DOC))).isTrue();

        assertThat(StorageKeys.isCanonical(null)).isFalse();
        assertThat(StorageKeys.isCanonical("")).isFalse();
        assertThat(StorageKeys.isCanonical("pending:" + UUID.randomUUID())).isFalse();
        assertThat(StorageKeys.isCanonical("organizations/evil")).isFalse();
        assertThat(StorageKeys.isCanonical("organizations/" + ORG + "/cases/" + CASE))
                .as("a truncated key addresses a prefix, not an object")
                .isFalse();
        assertThat(StorageKeys.isCanonical(
                "organizations/" + ORG + "/cases/" + CASE + "/documents/" + DOC + "/../other"))
                .isFalse();
        assertThat(StorageKeys.isCanonical(
                "organizations/" + ORG + "/cases/" + CASE + "/documents/not-a-uuid")).isFalse();
    }

    @Test
    @DisplayName("the guard is what stops an unstamped key from ever being signed for")
    void requireCanonicalRefusesAnythingElse() {
        assertThatThrownBy(() -> StorageKeys.requireCanonical(StorageKeys.reservation()))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> StorageKeys.requireCanonical(null))
                .isInstanceOf(IllegalStateException.class);

        // The one input it accepts.
        StorageKeys.requireCanonical(StorageKeys.forDocument(ORG, CASE, DOC));
    }

    @Test
    void refusesToBuildAKeyFromNothing() {
        assertThatThrownBy(() -> StorageKeys.forDocument(null, CASE, DOC))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StorageKeys.forDocument(ORG, null, DOC))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StorageKeys.forDocument(ORG, CASE, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
