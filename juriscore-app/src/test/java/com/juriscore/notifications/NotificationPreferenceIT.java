package com.juriscore.notifications;

import com.juriscore.casework.AbstractCaseworkIT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A user's own notification switches.
 *
 * <p>The strongest assertion in this class is the one about what is <em>not</em> here:
 * there is no endpoint taking another user's id, so an administrator cannot mute a
 * colleague. That is asserted by showing the two users' settings are independent rather
 * than by probing for a 403 on a route that does not exist.
 */
class NotificationPreferenceIT extends AbstractCaseworkIT {

    @Test
    @DisplayName("everything is on until you turn something off, and the version starts null")
    void defaultsAreEnabled() throws Exception {
        Firm firm = registerFirm("Sharma & Associates", "asha@sharma-legal.test");

        mockMvc.perform(get("/api/v1/notification-preferences")
                        .header("Authorization", bearer(firm.adminToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.invoice").value(true))
                .andExpect(jsonPath("$.data.payment").value(true))
                .andExpect(jsonPath("$.data.caseUpdates").value(true))
                .andExpect(jsonPath("$.data.system").value(true))
                .andExpect(jsonPath("$.data.version").doesNotExist());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM notifications.notification_preferences", Long.class))
                .as("reading defaults must not write a row — a firm's users cost nothing "
                        + "until one of them changes something")
                .isZero();
    }

    @Test
    void savingCreatesTheRowAndThenUpdatesIt() throws Exception {
        Firm firm = registerFirm("Sharma & Associates", "asha@sharma-legal.test");
        String token = firm.adminToken();

        mockMvc.perform(patch("/api/v1/notification-preferences")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"invoice\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.invoice").value(false))
                .andExpect(jsonPath("$.data.payment").value(true))
                .andExpect(jsonPath("$.data.version").value(0));

        mockMvc.perform(patch("/api/v1/notification-preferences")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"payment\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.invoice")
                        .value(false))
                .andExpect(jsonPath("$.data.payment").value(false));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM notifications.notification_preferences", Long.class))
                .as("one row per user, whatever the number of edits")
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("a PATCH leaves omitted switches alone rather than resetting them")
    void omittedSwitchesAreUntouched() throws Exception {
        Firm firm = registerFirm("Sharma & Associates", "asha@sharma-legal.test");
        String token = firm.adminToken();

        mockMvc.perform(patch("/api/v1/notification-preferences")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"invoice\":false,\"caseUpdates\":false}"))
                .andExpect(status().isOk());

        mockMvc.perform(patch("/api/v1/notification-preferences")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"system\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.invoice").value(false))
                .andExpect(jsonPath("$.data.caseUpdates").value(false))
                .andExpect(jsonPath("$.data.payment").value(true))
                .andExpect(jsonPath("$.data.system").value(false));
    }

    @Test
    void switchesGoBackOn() throws Exception {
        Firm firm = registerFirm("Sharma & Associates", "asha@sharma-legal.test");
        String token = firm.adminToken();

        mockMvc.perform(patch("/api/v1/notification-preferences")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"invoice\":false}"))
                .andExpect(status().isOk());
        mockMvc.perform(patch("/api/v1/notification-preferences")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"invoice\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.invoice").value(true));
    }

    @Test
    @DisplayName("preferences belong to the user: an administrator cannot mute a colleague")
    void preferencesAreNotTheFirms() throws Exception {
        Firm firm = registerFirm("Sharma & Associates", "asha@sharma-legal.test");
        String adminToken = firm.adminToken();
        String clerkToken = inviteAndActivate(firm, "clerk@sharma-legal.test", "CLERK");

        mockMvc.perform(patch("/api/v1/notification-preferences")
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"invoice\":false,\"payment\":false,"
                                + "\"caseUpdates\":false,\"system\":false}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/notification-preferences")
                        .header("Authorization", bearer(clerkToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.invoice").value(true))
                .andExpect(jsonPath("$.data.payment").value(true))
                .andExpect(jsonPath("$.data.caseUpdates").value(true))
                .andExpect(jsonPath("$.data.system").value(true));

        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM notifications.notification_preferences WHERE user_id = ?
                """, Long.class, userIdOf("clerk@sharma-legal.test")))
                .as("the administrator's edit touched only their own row")
                .isZero();
    }

    @Test
    @DisplayName("preferences are per user even across firms with the same roles")
    void preferencesAreScopedToTheUserAcrossFirms() throws Exception {
        Firm mine = registerFirm("Sharma & Associates", "asha@sharma-legal.test");
        Firm theirs = registerFirm("Kulkarni Chambers", "ravi@kulkarni-legal.test");

        mockMvc.perform(patch("/api/v1/notification-preferences")
                        .header("Authorization", bearer(mine.adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"invoice\":false}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/notification-preferences")
                        .header("Authorization", bearer(theirs.adminToken())))
                .andExpect(jsonPath("$.data.invoice").value(true));
    }

    @Test
    @DisplayName("every signed-in role manages its own switches — being told is not a privilege")
    void everyRoleCanManageItsOwn() throws Exception {
        Firm firm = registerFirm("Sharma & Associates", "asha@sharma-legal.test");
        String lawyer = inviteAndActivate(firm, "ravi@sharma-legal.test", "LAWYER");
        String clerk = inviteAndActivate(firm, "clerk@sharma-legal.test", "CLERK");
        String client = inviteAndActivate(firm, "portal@sharma-legal.test", "CLIENT");

        for (String token : new String[]{firm.adminToken(), lawyer, clerk, client}) {
            mockMvc.perform(get("/api/v1/notification-preferences")
                            .header("Authorization", bearer(token)))
                    .andExpect(status().isOk());
            mockMvc.perform(patch("/api/v1/notification-preferences")
                            .header("Authorization", bearer(token))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"system\":false}"))
                    .andExpect(status().isOk());
        }
    }

    @Test
    void bothEndpointsRequireAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/notification-preferences"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(patch("/api/v1/notification-preferences")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }
}
