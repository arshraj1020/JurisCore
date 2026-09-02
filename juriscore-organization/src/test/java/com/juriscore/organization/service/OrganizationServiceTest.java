package com.juriscore.organization.service;

import com.juriscore.common.error.ApiException;
import com.juriscore.common.error.ErrorCode;
import com.juriscore.organization.api.dto.UpdateOrganizationRequest;
import com.juriscore.organization.domain.Organization;
import com.juriscore.organization.domain.OrganizationStatus;
import com.juriscore.organization.repository.OrganizationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Firm provisioning and profile editing.
 *
 * <p>The interesting behaviour here is the handle: it is derived from a name people choose,
 * it has to be unique, and it is immutable once issued because it is the firm's identity in
 * a URL. The collision loop is where that goes wrong quietly, so it is exercised at every
 * branch — first try, counter, exhausted counter, and the point where it gives up.
 *
 * <p>Unit level on purpose: none of this needs a database. The unique index on
 * {@code slug} remains the real arbiter under concurrency and is exercised by
 * {@code OrganizationIT}, which registers two firms with the same name for real.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrganizationServiceTest {

    @Mock
    private OrganizationRepository repository;

    private OrganizationService organizationService;

    @BeforeEach
    void setUp() {
        organizationService = new OrganizationService(repository);
        when(repository.save(any(Organization.class))).thenAnswer(call -> call.getArgument(0));
    }

    @Test
    @DisplayName("the handle is derived from the firm name")
    void derivesAHandleFromTheFirmName() {
        when(repository.existsBySlug(anyString())).thenReturn(false);

        Organization firm = organizationService.create("Sharma & Associates", "asha@sharma-legal.test", null);

        assertThat(firm.getSlug()).isEqualTo("sharma-associates");
        assertThat(firm.getName()).isEqualTo("Sharma & Associates");
        assertThat(firm.getStatus()).isEqualTo(OrganizationStatus.ACTIVE);
    }

    @Test
    @DisplayName("the firm name is trimmed before it is stored")
    void trimsTheFirmName() {
        when(repository.existsBySlug(anyString())).thenReturn(false);

        assertThat(organizationService.create("  Sharma & Associates  ", null, null).getName())
                .isEqualTo("Sharma & Associates");
    }

    @Test
    @DisplayName("a taken handle gets a counter appended")
    void appendsACounterWhenTheHandleIsTaken() {
        when(repository.existsBySlug("sharma-associates")).thenReturn(true);
        when(repository.existsBySlug("sharma-associates-2")).thenReturn(false);

        assertThat(organizationService.create("Sharma & Associates", null, null).getSlug())
                .isEqualTo("sharma-associates-2");
    }

    @Test
    @DisplayName("the counter keeps walking until it finds a free handle")
    void walksTheCounterPastSeveralCollisions() {
        when(repository.existsBySlug(anyString()))
                .thenAnswer(call -> call.getArgument(0).toString().matches("sharma-associates(-[2-4])?"));

        assertThat(organizationService.create("Sharma & Associates", null, null).getSlug())
                .isEqualTo("sharma-associates-5");
    }

    @Test
    @DisplayName("an exhausted counter falls back to a random suffix rather than giving up")
    void fallsBackToARandomSuffixWhenTheCounterIsExhausted() {
        // Everything the counter can produce is taken; only the random suffix is free.
        when(repository.existsBySlug(anyString()))
                .thenAnswer(call -> call.getArgument(0).toString().matches("sharma-associates(-\\d{1,2})?"));

        String slug = organizationService.create("Sharma & Associates", null, null).getSlug();

        assertThat(slug).startsWith("sharma-associates-");
        assertThat(slug).doesNotMatch("sharma-associates-\\d{1,2}");
    }

    @Test
    @DisplayName("when even the random suffix collides it fails loudly rather than issuing a duplicate")
    void refusesWhenEveryCandidateIsTaken() {
        when(repository.existsBySlug(anyString())).thenReturn(true);

        assertThatThrownBy(() -> organizationService.create("Sharma & Associates", null, null))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.ORGANIZATION_SLUG_TAKEN));
    }

    @Test
    @DisplayName("a name with no usable characters still yields a handle")
    void namesWithNothingToSlugifyFallBackToFirm() {
        when(repository.existsBySlug(anyString())).thenReturn(false);

        assertThat(organizationService.create("!!! ???", null, null).getSlug()).isEqualTo("firm");
    }

    @Test
    @DisplayName("the timezone defaults when it is absent or blank")
    void defaultsTheTimezone() {
        when(repository.existsBySlug(anyString())).thenReturn(false);

        assertThat(organizationService.create("Firm One", null, null).getTimezone())
                .isEqualTo("Asia/Kolkata");
        assertThat(organizationService.create("Firm Two", null, "   ").getTimezone())
                .isEqualTo("Asia/Kolkata");
        assertThat(organizationService.create("Firm Three", null, "Europe/London").getTimezone())
                .isEqualTo("Europe/London");
    }

    @Test
    @DisplayName("looking up a firm that does not exist is a 404, not an empty result")
    void getByIdRaisesOrganizationNotFound() {
        when(repository.findById(any(UUID.class))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> organizationService.getById(UUID.randomUUID()))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.ORGANIZATION_NOT_FOUND));
    }

    @Test
    @DisplayName("an update applies the editable fields and leaves the handle alone")
    void updateAppliesEditableFieldsAndKeepsTheHandle() {
        Organization existing = existingFirm();
        when(repository.findById(existing.getId())).thenReturn(Optional.of(existing));

        Organization updated = organizationService.update(existing.getId(), new UpdateOrganizationRequest(
                "  Sharma Legal LLP  ", "billing@sharma-legal.test", "+91 22 1234 5678",
                "12 Fort Street", "Ballard Estate", "Mumbai", "Maharashtra", "India",
                "400001", "Europe/London", "REG-99"));

        assertThat(updated.getName()).isEqualTo("Sharma Legal LLP");
        assertThat(updated.getContactEmail()).isEqualTo("billing@sharma-legal.test");
        assertThat(updated.getCity()).isEqualTo("Mumbai");
        assertThat(updated.getPostalCode()).isEqualTo("400001");
        assertThat(updated.getRegistrationNumber()).isEqualTo("REG-99");
        assertThat(updated.getTimezone()).isEqualTo("Europe/London");
        assertThat(updated.getSlug())
                .as("the handle is the firm's identity in a URL and must survive a rename")
                .isEqualTo("sharma-associates");
    }

    @Test
    @DisplayName("a blank timezone in an update leaves the existing one in place")
    void blankTimezoneDoesNotClearTheExistingOne() {
        Organization existing = existingFirm();
        existing.setTimezone("Europe/London");
        when(repository.findById(existing.getId())).thenReturn(Optional.of(existing));

        Organization updated = organizationService.update(existing.getId(), new UpdateOrganizationRequest(
                "Sharma & Associates", null, null, null, null, null, null, null, null, "  ", null));

        assertThat(updated.getTimezone()).isEqualTo("Europe/London");
    }

    @Test
    @DisplayName("updating a firm that does not exist is a 404")
    void updateRaisesNotFoundForAnUnknownFirm() {
        when(repository.findById(any(UUID.class))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> organizationService.update(UUID.randomUUID(), new UpdateOrganizationRequest(
                "Anything", null, null, null, null, null, null, null, null, null, null)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.ORGANIZATION_NOT_FOUND));
    }

    private Organization existingFirm() {
        Organization organization = Organization.builder()
                .name("Sharma & Associates")
                .slug("sharma-associates")
                .status(OrganizationStatus.ACTIVE)
                .contactEmail("asha@sharma-legal.test")
                .build();
        organization.setId(UUID.randomUUID());
        return organization;
    }
}
