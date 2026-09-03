package com.juriscore.casework;

import com.juriscore.casework.event.ClientCreatedEvent;
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

/** The client book: creating, editing, removing, and never seeing another firm's. */
class ClientIT extends AbstractCaseworkIT {

    @Test
    void createsAClientAndReadsItBack() throws Exception {
        Firm firm = registerFirm("Sharma & Associates", "asha@sharma-legal.test");

        String clientId = createClient(firm.adminToken(), "Asha Menon", "asha@menon.test");

        mockMvc.perform(get("/api/v1/clients/" + clientId)
                        .header("Authorization", bearer(firm.adminToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.displayName").value("Asha Menon"))
                .andExpect(jsonPath("$.data.clientType").value("INDIVIDUAL"))
                .andExpect(jsonPath("$.data.city").value("Mumbai"));

        // Asserted against the column rather than the payload: Jackson serialises a null
        // field as present-with-null, so jsonPath().doesNotExist() would be checking
        // something other than what it appears to check.
        assertThat(deletedAt(clientId)).isNull();
        assertThat(events.require(ClientCreatedEvent.class).eventType()).isEqualTo("client.created");
    }

    @Test
    @DisplayName("the tenant column is written from the token, not from anything the caller sent")
    void theClientIsScopedToTheCallersFirm() throws Exception {
        Firm firm = registerFirm("Sharma & Associates", "asha@sharma-legal.test");

        String clientId = createClient(firm.adminToken(), "Asha Menon", "asha@menon.test");

        UUID organizationId = jdbcTemplate.queryForObject(
                "SELECT organization_id FROM casework.clients WHERE id = ?::uuid", UUID.class, clientId);
        assertThat(organizationId).hasToString(firm.id());
    }

    @Test
    void rejectsAClientWithNoName() throws Exception {
        Firm firm = registerFirm("Sharma & Associates", "asha@sharma-legal.test");

        mockMvc.perform(post("/api/v1/clients")
                        .header("Authorization", bearer(firm.adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"   \",\"clientType\":\"INDIVIDUAL\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void rejectsAClientWithNoType() throws Exception {
        Firm firm = registerFirm("Sharma & Associates", "asha@sharma-legal.test");

        mockMvc.perform(post("/api/v1/clients")
                        .header("Authorization", bearer(firm.adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"Asha Menon\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void rejectsAMalformedEmailAddress() throws Exception {
        Firm firm = registerFirm("Sharma & Associates", "asha@sharma-legal.test");

        mockMvc.perform(post("/api/v1/clients")
                        .header("Authorization", bearer(firm.adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"Asha\",\"clientType\":\"INDIVIDUAL\",\"email\":\"not-an-address\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("the same address twice in one firm is a conflict, whatever the capitalisation")
    void refusesADuplicateAddressWithinTheFirm() throws Exception {
        Firm firm = registerFirm("Sharma & Associates", "asha@sharma-legal.test");
        createClient(firm.adminToken(), "Asha Menon", "asha@menon.test");

        mockMvc.perform(post("/api/v1/clients")
                        .header("Authorization", bearer(firm.adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(clientBody("Asha M Menon", "ASHA@MENON.TEST")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("DUPLICATE_RESOURCE"));
    }

    @Test
    @DisplayName("two firms may both act for the same person — that is not a conflict")
    void theSameAddressInTwoFirmsIsFine() throws Exception {
        Firm mine = registerFirm("Sharma & Associates", "asha@sharma-legal.test");
        Firm theirs = registerFirm("Kulkarni Chambers", "ravi@kulkarni-legal.test");

        createClient(mine.adminToken(), "Asha Menon", "shared@client.test");
        createClient(theirs.adminToken(), "Asha Menon", "shared@client.test");
    }

    @Test
    @DisplayName("several clients with no address at all do not collide")
    void clientsWithoutAnAddressDoNotCollide() throws Exception {
        Firm firm = registerFirm("Sharma & Associates", "asha@sharma-legal.test");

        createClient(firm.adminToken(), "Trust One", null);
        createClient(firm.adminToken(), "Trust Two", null);

        mockMvc.perform(get("/api/v1/clients").header("Authorization", bearer(firm.adminToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalItems").value(2));
    }

    @Test
    void updatesAClient() throws Exception {
        Firm firm = registerFirm("Sharma & Associates", "asha@sharma-legal.test");
        String clientId = createClient(firm.adminToken(), "Asha Menon", "asha@menon.test");

        mockMvc.perform(put("/api/v1/clients/" + clientId)
                        .header("Authorization", bearer(firm.adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"displayName":"Asha Menon-Iyer","clientType":"CORPORATE",
                                 "email":"asha@menon.test","city":"Pune"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.displayName").value("Asha Menon-Iyer"))
                .andExpect(jsonPath("$.data.clientType").value("CORPORATE"))
                .andExpect(jsonPath("$.data.city").value("Pune"));
    }

    // ------------------------------------------------------------------- soft deletion

    @Test
    @DisplayName("deletion hides the client without removing the row")
    void deletionIsSoft() throws Exception {
        Firm firm = registerFirm("Sharma & Associates", "asha@sharma-legal.test");
        String clientId = createClient(firm.adminToken(), "Asha Menon", "asha@menon.test");

        mockMvc.perform(delete("/api/v1/clients/" + clientId)
                        .header("Authorization", bearer(firm.adminToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deletedAt").exists());

        Long remaining = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM casework.clients WHERE id = ?::uuid", Long.class, clientId);
        assertThat(remaining)
                .as("a hard delete would take every case that names this client with it")
                .isEqualTo(1L);
    }

    @Test
    void aDeletedClientDisappearsFromTheList() throws Exception {
        Firm firm = registerFirm("Sharma & Associates", "asha@sharma-legal.test");
        String kept = createClient(firm.adminToken(), "Kept Client", "kept@client.test");
        String removed = createClient(firm.adminToken(), "Removed Client", "removed@client.test");

        mockMvc.perform(delete("/api/v1/clients/" + removed)
                        .header("Authorization", bearer(firm.adminToken())))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/clients").header("Authorization", bearer(firm.adminToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalItems").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(kept));
    }

    @Test
    @DisplayName("a deleted client is still readable by id, so an old matter still resolves")
    void aDeletedClientRemainsReadableById() throws Exception {
        Firm firm = registerFirm("Sharma & Associates", "asha@sharma-legal.test");
        String clientId = createClient(firm.adminToken(), "Asha Menon", "asha@menon.test");
        String caseId = createCase(firm.adminToken(), clientId, "Menon v. Iyer");

        mockMvc.perform(delete("/api/v1/clients/" + clientId)
                        .header("Authorization", bearer(firm.adminToken())))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/clients/" + clientId)
                        .header("Authorization", bearer(firm.adminToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deletedAt").exists());

        mockMvc.perform(get("/api/v1/cases/" + caseId)
                        .header("Authorization", bearer(firm.adminToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.clientId").value(clientId));
    }

    @Test
    void aDeletedClientCannotBeChosenForANewCase() throws Exception {
        Firm firm = registerFirm("Sharma & Associates", "asha@sharma-legal.test");
        String clientId = createClient(firm.adminToken(), "Asha Menon", "asha@menon.test");

        mockMvc.perform(delete("/api/v1/clients/" + clientId)
                        .header("Authorization", bearer(firm.adminToken())))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/cases")
                        .header("Authorization", bearer(firm.adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"New matter\",\"clientId\":\"%s\"}".formatted(clientId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("CLIENT_NOT_FOUND"));
    }

    @Test
    void deletingTwiceIsNotFound() throws Exception {
        Firm firm = registerFirm("Sharma & Associates", "asha@sharma-legal.test");
        String clientId = createClient(firm.adminToken(), "Asha Menon", "asha@menon.test");

        mockMvc.perform(delete("/api/v1/clients/" + clientId)
                        .header("Authorization", bearer(firm.adminToken())))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/api/v1/clients/" + clientId)
                        .header("Authorization", bearer(firm.adminToken())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("CLIENT_NOT_FOUND"));
    }

    @Test
    @DisplayName("the address of a deleted client can be reused")
    void theAddressOfADeletedClientBecomesAvailableAgain() throws Exception {
        Firm firm = registerFirm("Sharma & Associates", "asha@sharma-legal.test");
        String clientId = createClient(firm.adminToken(), "Asha Menon", "asha@menon.test");

        mockMvc.perform(delete("/api/v1/clients/" + clientId)
                        .header("Authorization", bearer(firm.adminToken())))
                .andExpect(status().isOk());

        createClient(firm.adminToken(), "Asha Menon", "asha@menon.test");
    }

    // ----------------------------------------------------------------- tenant isolation

    @Test
    @DisplayName("another firm's client is not found — never forbidden, which would confirm it exists")
    void aForeignClientIsNotFound() throws Exception {
        Firm mine = registerFirm("Sharma & Associates", "asha@sharma-legal.test");
        Firm theirs = registerFirm("Kulkarni Chambers", "ravi@kulkarni-legal.test");
        String theirClient = createClient(theirs.adminToken(), "Their Client", "their@client.test");

        mockMvc.perform(get("/api/v1/clients/" + theirClient)
                        .header("Authorization", bearer(mine.adminToken())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("CLIENT_NOT_FOUND"));

        mockMvc.perform(put("/api/v1/clients/" + theirClient)
                        .header("Authorization", bearer(mine.adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(clientBody("Hijacked", "hijack@client.test")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("CLIENT_NOT_FOUND"));

        mockMvc.perform(delete("/api/v1/clients/" + theirClient)
                        .header("Authorization", bearer(mine.adminToken())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("CLIENT_NOT_FOUND"));
    }

    @Test
    void listsOnlyTheCallersOwnClients() throws Exception {
        Firm mine = registerFirm("Sharma & Associates", "asha@sharma-legal.test");
        Firm theirs = registerFirm("Kulkarni Chambers", "ravi@kulkarni-legal.test");
        createClient(mine.adminToken(), "My Client", "mine@client.test");
        createClient(theirs.adminToken(), "Their Client", "theirs@client.test");

        mockMvc.perform(get("/api/v1/clients").header("Authorization", bearer(mine.adminToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalItems").value(1))
                .andExpect(jsonPath("$.data.items[0].displayName").value("My Client"));
    }

    @Test
    void searchStaysInsideTheFirm() throws Exception {
        Firm mine = registerFirm("Sharma & Associates", "asha@sharma-legal.test");
        Firm theirs = registerFirm("Kulkarni Chambers", "ravi@kulkarni-legal.test");
        createClient(mine.adminToken(), "Asha Menon", "asha@menon.test");
        createClient(theirs.adminToken(), "Asha Menon", "asha2@menon.test");

        mockMvc.perform(get("/api/v1/clients").param("search", "menon")
                        .header("Authorization", bearer(mine.adminToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalItems").value(1));
    }

    private java.sql.Timestamp deletedAt(String clientId) {
        return jdbcTemplate.queryForObject(
                "SELECT deleted_at FROM casework.clients WHERE id = ?::uuid",
                java.sql.Timestamp.class, clientId);
    }

    @Test
    void anUnknownClientIdIsNotFound() throws Exception {
        Firm firm = registerFirm("Sharma & Associates", "asha@sharma-legal.test");

        mockMvc.perform(get("/api/v1/clients/" + UUID.randomUUID())
                        .header("Authorization", bearer(firm.adminToken())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("CLIENT_NOT_FOUND"));
    }
}
