package com.jurislpo.jurisone.tasks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Task settings (default steps, business labels; {@code tasks.manage}) and Team tasks (reporting line, depth 1 or 2). */
class TaskSettingsAndTeamApiTest extends TaskApiTestBase {

    static List<String> names(JsonNode statuses) {
        List<String> names = new ArrayList<>();
        statuses.forEach(s -> names.add(s.get("name").asText()));
        return names;
    }

    @Test
    void theOwnerChangesTheDefaultStepsForNewProjectsAndPersonalTasks() throws Exception {
        Person owner = person(ownerRole, "owner"), hr = person(hrRole, "hr");
        mockMvc.perform(get("/api/task-settings").cookie(hr.cookie())).andExpect(status().isForbidden());
        mockMvc.perform(write(post("/api/task-settings/workflow-statuses")).cookie(hr.cookie()).content("{\"name\":\"X\",\"category\":\"TODO\"}")).andExpect(status().isForbidden());
        // a new business: the built-in steps, not stored yet
        JsonNode settings = getData(owner.cookie(), "/api/task-settings");
        assertThat(names(settings.get("defaultStatuses"))).containsExactly("To do", "In progress", "In review", "Done", "Cancelled");
        assertThat(settings.get("defaultStatuses").get(0).get("id").isNull()).isTrue();
        String existing = project(hr.cookie(), "OLD", "PRIVATE"); // copies the default set (and stores it)
        String waiting = postCreated(owner.cookie(), "/api/task-settings/workflow-statuses", "{\"name\":\"Waiting\",\"category\":\"IN_PROGRESS\",\"color\":\"amber\"}")
                .get("id").asText();
        mockMvc.perform(write(put("/api/task-settings/workflow-statuses/" + statusId(null, "In review"))).cookie(owner.cookie())
                .content("{\"name\":\"Checking\",\"category\":\"IN_PROGRESS\",\"color\":\"purple\"}")).andExpect(status().isOk());
        String personal = task(hr.cookie(), "\"title\":\"Personal\",\"statusId\":\"" + statusId(null, "Cancelled") + "\"").get("id").asText();
        mockMvc.perform(write(delete("/api/task-settings/workflow-statuses/" + statusId(null, "Cancelled"))).cookie(owner.cookie())).andExpect(status().isUnprocessableEntity());
        mockMvc.perform(write(delete("/api/task-settings/workflow-statuses/" + statusId(null, "Cancelled") + "?moveTasksToStatusId=" + waiting)).cookie(owner.cookie()))
                .andExpect(status().isOk());
        assertThat(getData(hr.cookie(), "/api/tasks/" + personal).get("statusName").asText()).isEqualTo("Waiting");
        settings = getData(owner.cookie(), "/api/task-settings");
        assertThat(names(settings.get("defaultStatuses"))).containsExactly("To do", "In progress", "Checking", "Done", "Waiting");
        List<String> ids = new ArrayList<>();
        settings.get("defaultStatuses").forEach(s -> ids.add(s.get("id").asText()));
        java.util.Collections.reverse(ids);
        mockMvc.perform(write(put("/api/task-settings/workflow-status-order")).cookie(owner.cookie()).content("{\"statusIds\":[\"" + String.join("\",\"", ids) + "\"]}"))
                .andExpect(status().isOk());
        // new projects get the new set; existing ones keep theirs
        String fresh = project(hr.cookie(), "NEW", "PRIVATE");
        assertThat(names(getData(hr.cookie(), "/api/projects/" + fresh).get("statuses"))).containsExactly("Waiting", "Done", "Checking", "In progress", "To do");
        assertThat(names(getData(hr.cookie(), "/api/projects/" + existing).get("statuses"))).containsExactly("To do", "In progress", "In review", "Done", "Cancelled");
        // business labels show in the settings; counts
        postCreated(owner.cookie(), "/api/task-labels", "{\"name\":\"Compliance\",\"color\":\"green\"}");
        settings = getData(owner.cookie(), "/api/task-settings");
        assertThat(settings.get("labels").get(0).get("name").asText()).isEqualTo("Compliance");
        assertThat(settings.get("projectCount").asInt()).isEqualTo(2);
        assertThat(settings.get("taskCount").asInt()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action LIKE 'workflow_status.%' AND actor_id = ?", Integer.class, owner.id())).isEqualTo(4);
    }

    @Test
    void managersSeeTheirTeamsTasksDirectOrTwoLevelsDown() throws Exception {
        Person hr = person(hrRole, "hr"), manager = person(employeeRole, "manager"), report = person(employeeRole, "report"), deeper = person(employeeRole, "deeper"),
                loner = person(employeeRole, "loner");
        reportsTo(report, manager);
        reportsTo(deeper, report);
        String projectId = project(hr.cookie(), "TEAM", "PRIVATE");
        task(hr.cookie(), "\"projectId\":\"" + projectId + "\",\"title\":\"Report work\",\"assigneeId\":\"" + report.id() + "\",\"indirectAssigneeId\":\"" + manager.id()
                + "\",\"dueDate\":\"2020-01-01\"");
        task(hr.cookie(), "\"projectId\":\"" + projectId + "\",\"title\":\"Deeper work\",\"assigneeId\":\"" + deeper.id() + "\"");
        task(hr.cookie(), "\"title\":\"Not the team\",\"assigneeId\":\"" + loner.id() + "\"");
        // the manager has reports now: sign in again so the session sees them
        Person managerAgain = new Person(manager.id(), manager.email(), signIn(manager.email()));
        JsonNode direct = getData(managerAgain.cookie(), "/api/tasks/team");
        assertThat(direct.get("rows").findValuesAsText("title")).containsExactly("Report work");
        assertThat(direct.get("people").size()).isEqualTo(1);
        assertThat(direct.get("people").get(0).get("open").asInt()).isEqualTo(1);
        assertThat(direct.get("people").get(0).get("overdue").asInt()).isEqualTo(1);
        JsonNode both = getData(managerAgain.cookie(), "/api/tasks/team?depth=2");
        assertThat(both.get("rows").findValuesAsText("title")).containsExactlyInAnyOrder("Report work", "Deeper work");
        assertThat(both.get("people").size()).isEqualTo(2);
        assertThat(getData(managerAgain.cookie(), "/api/tasks/team?depth=2&employeeId=" + deeper.id()).get("total").asInt()).isEqualTo(1);
        assertThat(getData(managerAgain.cookie(), "/api/tasks/team?depth=2&employeeId=" + loner.id()).get("total").asInt()).isZero();
        assertThat(getData(managerAgain.cookie(), "/api/tasks/team?depth=2&indirectAssigneeId=" + manager.id()).get("total").asInt()).isEqualTo(1);
        // the page needs reports
        mockMvc.perform(get("/api/tasks/team").cookie(loner.cookie())).andExpect(status().isForbidden());
    }
}
