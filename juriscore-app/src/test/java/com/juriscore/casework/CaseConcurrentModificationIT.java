package com.juriscore.casework;

import com.juriscore.casework.domain.LegalCase;
import com.juriscore.casework.repository.CaseRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * "Two lawyers editing the same case get a {@code CONCURRENT_MODIFICATION} conflict
 * rather than a silent overwrite" is a promise the README makes. Until now nothing
 * tested it against a real row.
 *
 * <p>It is tested at both levels, because they can fail independently:
 *
 * <ul>
 *   <li>Over HTTP, where the second writer sends the version it read and must be told
 *       that the world moved. This is the level a frontend experiences.</li>
 *   <li>In the database, with two real transactions and a genuinely stale entity, which
 *       is what {@code @Version} on {@code BaseEntity} is actually for. A Mockito test
 *       cannot reach this: a mocked repository has no transaction to lose a race in.</li>
 * </ul>
 */
class CaseConcurrentModificationIT extends AbstractCaseworkIT {

    @Autowired
    private CaseRepository caseRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("over HTTP: the second writer is told, and does not overwrite")
    void aStaleWriterGetsAConflictInsteadOfWinning() throws Exception {
        Firm firm = registerFirm("Sharma & Associates", "asha@sharma-legal.test");
        String clientId = createClient(firm.adminToken(), "Asha Menon", "asha@menon.test");
        String caseId = createCase(firm.adminToken(), clientId, "Menon v. Iyer");

        // Both lawyers open the matter and see the same version.
        long versionBothRead = version(caseId, firm.adminToken());

        mockMvc.perform(put("/api/v1/cases/" + caseId)
                        .header("Authorization", bearer(firm.adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(edit("Amended by the first lawyer", clientId, versionBothRead)))
                .andExpect(status().isOk());

        mockMvc.perform(put("/api/v1/cases/" + caseId)
                        .header("Authorization", bearer(firm.adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(edit("Amended by the second lawyer", clientId, versionBothRead)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CONCURRENT_MODIFICATION"));

        mockMvc.perform(get("/api/v1/cases/" + caseId)
                        .header("Authorization", bearer(firm.adminToken())))
                .andExpect(jsonPath("$.data.title").value("Amended by the first lawyer"));
    }

    @Test
    @DisplayName("the losing writer can retry once it has re-read")
    void theConflictIsRecoverable() throws Exception {
        Firm firm = registerFirm("Sharma & Associates", "asha@sharma-legal.test");
        String clientId = createClient(firm.adminToken(), "Asha Menon", "asha@menon.test");
        String caseId = createCase(firm.adminToken(), clientId, "Menon v. Iyer");
        long stale = version(caseId, firm.adminToken());

        mockMvc.perform(put("/api/v1/cases/" + caseId)
                        .header("Authorization", bearer(firm.adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(edit("First", clientId, stale)))
                .andExpect(status().isOk());

        mockMvc.perform(put("/api/v1/cases/" + caseId)
                        .header("Authorization", bearer(firm.adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(edit("Second", clientId, version(caseId, firm.adminToken()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("Second"));
    }

    @Test
    @DisplayName("in the database: a stale entity written from a second transaction is refused")
    void aStaleEntityCannotOverwriteACommittedChange() throws Exception {
        Firm firm = registerFirm("Sharma & Associates", "asha@sharma-legal.test");
        String clientId = createClient(firm.adminToken(), "Asha Menon", "asha@menon.test");
        UUID caseId = UUID.fromString(createCase(firm.adminToken(), clientId, "Menon v. Iyer"));

        TransactionTemplate transactions = new TransactionTemplate(transactionManager);

        // The first lawyer loads the matter and goes to lunch. The entity is now detached
        // and carries the version it saw.
        LegalCase stale = transactions.execute(status ->
                caseRepository.findById(caseId).orElseThrow());

        // The second lawyer edits and commits.
        transactions.executeWithoutResult(status -> {
            LegalCase fresh = caseRepository.findById(caseId).orElseThrow();
            fresh.setTitle("Amended by the lawyer who was at their desk");
        });

        // The first lawyer comes back and saves.
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            stale.setTitle("Amended by the lawyer who was at lunch");
            caseRepository.saveAndFlush(stale);
        }))
                .as("without @Version this write would land and the committed edit would vanish")
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT title FROM casework.cases WHERE id = ?", String.class, caseId))
                .isEqualTo("Amended by the lawyer who was at their desk");
    }

    @Test
    @DisplayName("the version the API reports is the one the database is holding")
    void theReportedVersionTracksTheRow() throws Exception {
        Firm firm = registerFirm("Sharma & Associates", "asha@sharma-legal.test");
        String clientId = createClient(firm.adminToken(), "Asha Menon", "asha@menon.test");
        String caseId = createCase(firm.adminToken(), clientId, "Menon v. Iyer");

        long before = version(caseId, firm.adminToken());
        mockMvc.perform(put("/api/v1/cases/" + caseId)
                        .header("Authorization", bearer(firm.adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(edit("Amended", clientId, before)))
                .andExpect(status().isOk());

        assertThat(version(caseId, firm.adminToken())).isGreaterThan(before);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT version FROM casework.cases WHERE id = ?::uuid", Long.class, caseId))
                .isEqualTo(version(caseId, firm.adminToken()));
    }

    private String edit(String title, String clientId, long version) {
        return """
                {"title":"%s","description":"Amended particulars","clientId":"%s","version":%d}
                """.formatted(title, clientId, version);
    }

    private long version(String caseId, String token) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/cases/" + caseId)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn();
        return json(result).path("data").path("version").asLong();
    }
}
