package com.juriscore.casemanagement;

import com.juriscore.casemanagement.event.CourtCreatedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The firm's list of courts: adding, editing, retiring, and never seeing another firm's. */
class CourtIT extends AbstractCaseManagementIT {

    @Test
    void createsACourtAndReadsItBack() throws Exception {
        Firm firm = registerFirm("Sharma & Associates", "asha@sharma-legal.test");

        String courtId = createCourt(firm.adminToken(), "City Civil Court");

        mockMvc.perform(get("/api/v1/courts/" + courtId)
                        .header("Authorization", bearer(firm.adminToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("City Civil Court"))
                .andExpect(jsonPath("$.data.courtType").value("DISTRICT"))
                .andExpect(jsonPath("$.data.city").value("Mumbai"))
                .andExpect(jsonPath("$.data.active").value(true));

        assertThat(events.require(CourtCreatedEvent.class).eventType()).isEqualTo("court.created");
    }

    @Test
    @DisplayName("the tenant column is written from the token, not from anything the caller sent")
    void theCourtIsScopedToTheCallersFirm() throws Exception {
        Firm firm = registerFirm("Sharma & Associates", "asha@sharma-legal.test");
        String courtId = createCourt(firm.adminToken(), "City Civil Court");

        UUID organizationId = jdbcTemplate.queryForObject(
                "SELECT organization_id FROM case_management.courts WHERE id = ?::uuid",
                UUID.class, courtId);
        assertThat(organizationId).hasToString(firm.id());
    }

    @Test
    void rejectsACourtWithNoNameOrNoType() throws Exception {
        Firm firm = registerFirm("Sharma & Associates", "asha@sharma-legal.test");

        mockMvc.perform(post("/api/v1/courts").header("Authorization", bearer(firm.adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"   \",\"courtType\":\"DISTRICT\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

        mockMvc.perform(post("/api/v1/courts").header("Authorization", bearer(firm.adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"City Civil Court\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void rejectsACourtTypeThatIsNotOneOfTheFive() throws Exception {
        Firm firm = registerFirm("Sharma & Associates", "asha@sharma-legal.test");

        mockMvc.perform(post("/api/v1/courts").header("Authorization", bearer(firm.adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Star Chamber\",\"courtType\":\"ECCLESIASTICAL\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("MALFORMED_REQUEST"));
    }

    @Test
    @DisplayName("the same name twice in one firm is a conflict, whatever the capitalisation")
    void refusesADuplicateNameWithinTheFirm() throws Exception {
        Firm firm = registerFirm("Sharma & Associates", "asha@sharma-legal.test");
        createCourt(firm.adminToken(), "City Civil Court");

        mockMvc.perform(post("/api/v1/courts").header("Authorization", bearer(firm.adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(courtBody("CITY CIVIL COURT", null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("DUPLICATE_RESOURCE"));
    }

    @Test
    @DisplayName("two firms may both appear before the same bench — that is not a conflict")
    void theSameNameInTwoFirmsIsFine() throws Exception {
        Firm mine = registerFirm("Sharma & Associates", "asha@sharma-legal.test");
        Firm theirs = registerFirm("Kulkarni Chambers", "ravi@kulkarni-legal.test");

        createCourt(mine.adminToken(), "City Civil Court");
        createCourt(theirs.adminToken(), "City Civil Court");
    }

    @Test
    void updatesACourt() throws Exception {
        Firm firm = registerFirm("Sharma & Associates", "asha@sharma-legal.test");
        String courtId = createCourt(firm.adminToken(), "City Civil Court");

        mockMvc.perform(put("/api/v1/courts/" + courtId)
                        .header("Authorization", bearer(firm.adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(courtBody("City Sessions Court", versionOf("courts", courtId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("City Sessions Court"));
    }

    @Test
    @DisplayName("a stale version is a 409, not a silent overwrite")
    void updateRefusesAStaleVersion() throws Exception {
        Firm firm = registerFirm("Sharma & Associates", "asha@sharma-legal.test");
        String courtId = createCourt(firm.adminToken(), "City Civil Court");
        long stale = versionOf("courts", courtId);

        mockMvc.perform(put("/api/v1/courts/" + courtId)
                        .header("Authorization", bearer(firm.adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(courtBody("First edit", stale)))
                .andExpect(status().isOk());

        mockMvc.perform(put("/api/v1/courts/" + courtId)
                        .header("Authorization", bearer(firm.adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(courtBody("Second edit", stale)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CONCURRENT_MODIFICATION"));

        mockMvc.perform(get("/api/v1/courts/" + courtId)
                        .header("Authorization", bearer(firm.adminToken())))
                .andExpect(jsonPath("$.data.name").value("First edit"));
    }

    // ------------------------------------------------------------------------ retiring

    @Test
    @DisplayName("retirement deactivates the row rather than removing it")
    void retirementIsDeactivation() throws Exception {
        Firm firm = registerFirm("Sharma & Associates", "asha@sharma-legal.test");
        String courtId = createCourt(firm.adminToken(), "City Civil Court");

        mockMvc.perform(delete("/api/v1/courts/" + courtId)
                        .header("Authorization", bearer(firm.adminToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.active").value(false));

        Long remaining = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM case_management.courts WHERE id = ?::uuid", Long.class, courtId);
        assertThat(remaining)
                .as("a hard delete would break every hearing held before this bench")
                .isEqualTo(1L);
    }

    @Test
    void aRetiredCourtDisappearsFromTheListButStaysReadable() throws Exception {
        Firm firm = registerFirm("Sharma & Associates", "asha@sharma-legal.test");
        String kept = createCourt(firm.adminToken(), "Kept Court");
        String retired = createCourt(firm.adminToken(), "Retired Court");

        mockMvc.perform(delete("/api/v1/courts/" + retired)
                        .header("Authorization", bearer(firm.adminToken())))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/courts").header("Authorization", bearer(firm.adminToken())))
                .andExpect(jsonPath("$.data.totalItems").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(kept));

        mockMvc.perform(get("/api/v1/courts").param("includeRetired", "true")
                        .header("Authorization", bearer(firm.adminToken())))
                .andExpect(jsonPath("$.data.totalItems").value(2));

        mockMvc.perform(get("/api/v1/courts/" + retired)
                        .header("Authorization", bearer(firm.adminToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.active").value(false));
    }

    @Test
    @DisplayName("a court with listings ahead of it cannot be retired out from under them")
    void refusesToRetireACourtWithScheduledHearings() throws Exception {
        Matter matter = openMatter("Sharma & Associates", "asha@sharma-legal.test");
        String courtId = createCourt(matter.firm().adminToken(), "City Civil Court");
        String hearingId = scheduleHearing(matter.firm().adminToken(), matter.caseId(), courtId,
                NEXT_WEEK);

        mockMvc.perform(delete("/api/v1/courts/" + courtId)
                        .header("Authorization", bearer(matter.firm().adminToken())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ILLEGAL_STATE_TRANSITION"));

        // Once the listing is dealt with, the court can go.
        moveHearing(matter.firm().adminToken(), hearingId, "COMPLETED", 200);
        mockMvc.perform(delete("/api/v1/courts/" + courtId)
                        .header("Authorization", bearer(matter.firm().adminToken())))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("the name of a retired court can be reused")
    void theNameOfARetiredCourtBecomesAvailableAgain() throws Exception {
        Firm firm = registerFirm("Sharma & Associates", "asha@sharma-legal.test");
        String courtId = createCourt(firm.adminToken(), "City Civil Court");

        mockMvc.perform(delete("/api/v1/courts/" + courtId)
                        .header("Authorization", bearer(firm.adminToken())))
                .andExpect(status().isOk());

        createCourt(firm.adminToken(), "City Civil Court");
    }

    @Test
    void retiringTwiceIsNotFound() throws Exception {
        Firm firm = registerFirm("Sharma & Associates", "asha@sharma-legal.test");
        String courtId = createCourt(firm.adminToken(), "City Civil Court");

        mockMvc.perform(delete("/api/v1/courts/" + courtId)
                        .header("Authorization", bearer(firm.adminToken())))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/api/v1/courts/" + courtId)
                        .header("Authorization", bearer(firm.adminToken())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
    }

    // ---------------------------------------------------------------- tenant isolation

    @Test
    @DisplayName("another firm's court is not found — never forbidden, which would confirm it exists")
    void aForeignCourtIsNotFound() throws Exception {
        Firm mine = registerFirm("Sharma & Associates", "asha@sharma-legal.test");
        Firm theirs = registerFirm("Kulkarni Chambers", "ravi@kulkarni-legal.test");
        String theirCourt = createCourt(theirs.adminToken(), "Their Court");

        mockMvc.perform(get("/api/v1/courts/" + theirCourt)
                        .header("Authorization", bearer(mine.adminToken())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));

        mockMvc.perform(put("/api/v1/courts/" + theirCourt)
                        .header("Authorization", bearer(mine.adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(courtBody("Hijacked", 0L)))
                .andExpect(status().isNotFound());

        mockMvc.perform(delete("/api/v1/courts/" + theirCourt)
                        .header("Authorization", bearer(mine.adminToken())))
                .andExpect(status().isNotFound());
    }

    @Test
    void listsOnlyTheCallersOwnCourts() throws Exception {
        Firm mine = registerFirm("Sharma & Associates", "asha@sharma-legal.test");
        Firm theirs = registerFirm("Kulkarni Chambers", "ravi@kulkarni-legal.test");
        createCourt(mine.adminToken(), "My Court");
        createCourt(theirs.adminToken(), "Their Court");

        mockMvc.perform(get("/api/v1/courts").header("Authorization", bearer(mine.adminToken())))
                .andExpect(jsonPath("$.data.totalItems").value(1))
                .andExpect(jsonPath("$.data.items[0].name").value("My Court"));
    }

    @Test
    void anUnknownCourtIdIsIndistinguishableFromAForeignOne() throws Exception {
        Firm firm = registerFirm("Sharma & Associates", "asha@sharma-legal.test");

        mockMvc.perform(get("/api/v1/courts/" + UUID.randomUUID())
                        .header("Authorization", bearer(firm.adminToken())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
    }
}
