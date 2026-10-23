package com.jurislpo.jurisone.tasks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Comments, @mentions, followers and bell notifications (plan §10, §12.1 C1–C4). No emails. */
class TaskCollaborationApiTest extends TaskApiTestBase {

    int notifications(UUID employeeId, String like) {
        return jdbc.queryForObject("SELECT count(*) FROM notifications WHERE employee_id = ? AND text LIKE ?", Integer.class, employeeId, like);
    }

    @Test
    void commentsAreEditedByTheirAuthorAndDeletedByTheAuthorOrAProjectOwner() throws Exception {
        Person hr = person(hrRole, "hr"), member = person(employeeRole, "member"), colleague = person(employeeRole, "colleague");
        String projectId = project(hr.cookie(), "COM", "BUSINESS");
        addMember(hr.cookie(), projectId, member, "MEMBER");
        String id = task(hr.cookie(), "\"projectId\":\"" + projectId + "\",\"title\":\"Discuss\"").get("id").asText();
        String comments = "/api/tasks/" + id + "/comments";
        String first = postCreated(member.cookie(), comments, "{\"body\":\"First thought\"}").get("id").asText();
        String second = postCreated(colleague.cookie(), comments, "{\"body\":\"A viewer may comment on a company-wide project\"}").get("id").asText();
        mockMvc.perform(write(post(comments)).cookie(member.cookie()).content("{\"body\":\"   \"}")).andExpect(status().isBadRequest());
        mockMvc.perform(write(post(comments)).cookie(member.cookie()).content("{\"body\":\"" + "x".repeat(5001) + "\"}")).andExpect(status().isBadRequest());
        mockMvc.perform(write(patch(comments + "/" + first)).cookie(member.cookie()).content("{\"body\":\"Second thought\"}")).andExpect(status().isOk());
        mockMvc.perform(write(patch(comments + "/" + first)).cookie(hr.cookie()).content("{\"body\":\"Not mine\"}")).andExpect(status().isForbidden());
        mockMvc.perform(write(delete(comments + "/" + first)).cookie(colleague.cookie())).andExpect(status().isForbidden());
        mockMvc.perform(write(delete(comments + "/" + second)).cookie(hr.cookie())).andExpect(status().isOk()); // project owner
        mockMvc.perform(write(delete(comments + "/" + second)).cookie(hr.cookie())).andExpect(status().isNotFound());
        JsonNode page = getData(member.cookie(), comments);
        assertThat(page.get("total").asInt()).isEqualTo(2);
        JsonNode rows = page.get("rows");
        assertThat(rows.get(0).get("body").asText()).isEqualTo("Second thought");
        assertThat(rows.get(0).get("editedAt").isNull()).isFalse();
        assertThat(rows.get(0).get("canEdit").asBoolean()).isTrue();
        assertThat(rows.get(1).get("isDeleted").asBoolean()).isTrue();
        assertThat(rows.get(1).get("body").isNull()).isTrue();
        assertThat(rows.get(1).get("canDelete").asBoolean()).isFalse();
        assertThat(getData(member.cookie(), "/api/tasks/" + id).get("commentCount").asInt()).isEqualTo(1);
        // the history shows the deletion; the audit log keeps lengths, never the text
        assertThat(getData(hr.cookie(), "/api/tasks/" + id + "/activity").get("rows").get(0).get("kind").asText()).isEqualTo("COMMENT_DELETED");
        assertThat(jdbc.queryForObject("SELECT after->>'length' FROM audit_log WHERE action = 'task.comment_add' AND entity_id = ? ORDER BY at LIMIT 1", String.class, id))
                .isEqualTo("13");
        assertThat(auditRows("task.comment_edit", id)).isEqualTo(1);
        assertThat(auditRows("task.comment_delete", id)).isEqualTo(1);
        // archived projects take no comments
        mockMvc.perform(write(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/projects/" + projectId + "/status")).cookie(hr.cookie())
                .content("{\"status\":\"ARCHIVED\"}")).andExpect(status().isOk());
        mockMvc.perform(write(post(comments)).cookie(member.cookie()).content("{\"body\":\"Too late\"}")).andExpect(status().isUnprocessableEntity());
    }

    @Test
    void mentionsReachOnlyPeopleWhoCanSeeTheTaskAndTheActorIsNeverNotified() throws Exception {
        Person hr = person(hrRole, "hr"), doer = person(employeeRole, "doer"), accountable = person(employeeRole, "accountable"),
                member = person(employeeRole, "member"), outsider = person(employeeRole, "outsider");
        String projectId = project(hr.cookie(), "MEN", "PRIVATE");
        addMember(hr.cookie(), projectId, member, "VIEWER");
        String id = task(hr.cookie(), "\"projectId\":\"" + projectId + "\",\"title\":\"Contract review\",\"assigneeId\":\"" + doer.id() + "\",\"indirectAssigneeId\":\""
                + accountable.id() + "\"").get("id").asText();
        assertThat(notifications(hr.id(), "%")).isZero(); // the actor is never told
        postCreated(doer.cookie(), "/api/tasks/" + id + "/comments", "{\"body\":\"@{" + member.id() + "} and @{" + outsider.id() + "} please look\"}");
        // the visible person is mentioned (and now follows); the outsider learns nothing and gets no access
        assertThat(notifications(member.id(), "%mentioned you on MEN-1: Contract review")).isEqualTo(1);
        assertThat(notifications(outsider.id(), "%")).isZero();
        org.springframework.test.web.servlet.ResultActions outsiderRead = mockMvc.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/tasks/" + id).cookie(outsider.cookie()));
        outsiderRead.andExpect(status().isNotFound());
        JsonNode comment = getData(doer.cookie(), "/api/tasks/" + id + "/comments").get("rows").get(0);
        assertThat(comment.get("mentionedPeople").has(member.id().toString())).isTrue();
        assertThat(comment.get("mentionedPeople").has(outsider.id().toString())).isFalse();
        // followers (reporter, accountable) hear about the comment once; the commenter does not
        assertThat(notifications(hr.id(), "%commented on MEN-1%")).isEqualTo(1);
        assertThat(notifications(accountable.id(), "%commented on MEN-1%")).isEqualTo(1);
        assertThat(notifications(doer.id(), "%commented on%")).isZero();
        // a status change tells the followers, both assignees included, not the actor
        mockMvc.perform(write(patch("/api/tasks/" + id)).cookie(hr.cookie()).content("{\"version\":0,\"statusCategory\":\"DONE\"}")).andExpect(status().isOk());
        assertThat(notifications(doer.id(), "MEN-1 moved to Done by %")).isEqualTo(1);
        assertThat(notifications(accountable.id(), "MEN-1 moved to Done by %")).isEqualTo(1);
        assertThat(notifications(member.id(), "MEN-1 moved to Done by %")).isEqualTo(1);
        assertThat(notifications(hr.id(), "%moved to%")).isZero();
        // a description mention works the same way
        mockMvc.perform(write(patch("/api/tasks/" + id)).cookie(hr.cookie()).content("{\"version\":1,\"description\":\"Ask @{" + member.id() + "}\"}")).andExpect(status().isOk());
        assertThat(notifications(member.id(), "%mentioned you on MEN-1%")).isEqualTo(2);
        assertThat(getData(hr.cookie(), "/api/tasks/" + id).get("mentionedPeople").has(member.id().toString())).isTrue();
        // bell only: nothing in the email outbox for any of them
        assertThat(jdbc.queryForObject("SELECT count(*) FROM email_outbox WHERE \"to\" IN (?, ?, ?, ?, ?) AND subject NOT LIKE '%sign-in code%'", Integer.class,
                hr.email(), doer.email(), accountable.email(), member.email(), outsider.email())).isZero();
    }

    @Test
    void oneRequestSendsAtMostOneNotificationPerPerson() throws Exception {
        Person hr = person(hrRole, "hr"), doer = person(employeeRole, "doer");
        String projectId = project(hr.cookie(), "ONE", "PRIVATE");
        String id = task(hr.cookie(), "\"projectId\":\"" + projectId + "\",\"title\":\"Once\",\"description\":\"For @{" + doer.id() + "}\",\"assigneeId\":\"" + doer.id() + "\"")
                .get("id").asText();
        // assigned and mentioned in the same request: one bell line, the mention
        assertThat(notifications(doer.id(), "%")).isEqualTo(1);
        assertThat(notifications(doer.id(), "%mentioned you on ONE-1%")).isEqualTo(1);
        // the follower list: reporter, assignee
        assertThat(getData(hr.cookie(), "/api/tasks/" + id).get("watchers").size()).isEqualTo(2);
    }
}
