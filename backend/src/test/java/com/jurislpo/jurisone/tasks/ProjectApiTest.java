package com.jurislpo.jurisone.tasks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Projects, members and workflow steps (plan §12.1 P1–P14). */
class ProjectApiTest extends TaskApiTestBase {

    @Test
    void onlyTheOwnerAndHrManagerCreateProjectsWithUniqueKeysAndNames() throws Exception {
        Person owner = person(ownerRole, "owner"), hr = person(hrRole, "hr"), finance = person(financeRole, "finance"), employee = person(employeeRole, "emp");
        JsonNode created = postCreated(owner.cookie(), "/api/v1/projects", "{\"key\":\"pay\",\"name\":\"Payroll October\",\"visibility\":\"PRIVATE\",\"color\":\"blue\"}");
        assertThat(created.get("key").asText()).isEqualTo("PAY"); // keys are upper-cased
        postCreated(hr.cookie(), "/api/v1/projects", "{\"key\":\"HR\",\"name\":\"People operations\",\"visibility\":\"BUSINESS\"}");
        // Finance Manager and Employee do not hold projects.create
        mockMvc.perform(write(post("/api/v1/projects")).cookie(finance.cookie()).content("{\"key\":\"FIN\",\"name\":\"Finance\"}")).andExpect(status().isForbidden());
        mockMvc.perform(write(post("/api/v1/projects")).cookie(employee.cookie()).content("{\"key\":\"EMP\",\"name\":\"Mine\"}")).andExpect(status().isForbidden());
        // duplicates and bad input
        mockMvc.perform(write(post("/api/v1/projects")).cookie(owner.cookie()).content("{\"key\":\"PAY\",\"name\":\"Another\"}")).andExpect(status().isConflict());
        mockMvc.perform(write(post("/api/v1/projects")).cookie(owner.cookie()).content("{\"key\":\"PAY2\",\"name\":\"payroll october\"}")).andExpect(status().isConflict());
        mockMvc.perform(write(post("/api/v1/projects")).cookie(owner.cookie()).content("{\"key\":\"1X\",\"name\":\"Bad key\"}")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mockMvc.perform(write(post("/api/v1/projects")).cookie(owner.cookie()).content("{\"key\":\"OK\",\"name\":\" \"}")).andExpect(status().isBadRequest());
        mockMvc.perform(write(post("/api/v1/projects")).cookie(owner.cookie()).content("{\"key\":\"OK\",\"name\":\"Fine\",\"visibility\":\"PUBLIC\"}")).andExpect(status().isBadRequest());
        mockMvc.perform(write(post("/api/v1/projects")).cookie(owner.cookie()).content("{\"key\":\"OK\",\"name\":\"Fine\",\"color\":\"pink\"}")).andExpect(status().isBadRequest());
        assertThat(auditRows("project.create", created.get("id").asText())).isEqualTo(1);
        // the creator is the project's owner and the default steps were copied
        JsonNode detail = getData(owner.cookie(), "/api/v1/projects/" + created.get("id").asText());
        assertThat(detail.get("myRole").asText()).isEqualTo("OWNER");
        assertThat(detail.get("canManage").asBoolean()).isTrue();
        assertThat(detail.get("ownerNames").get(0).asText()).startsWith("Person");
        List<String> steps = new ArrayList<>();
        detail.get("statuses").forEach(s -> steps.add(s.get("name").asText()));
        assertThat(steps).containsExactly("To do", "In progress", "In review", "Done", "Cancelled");
    }

    @Test
    void privateProjectsAreHiddenFromNonMembersAndCompanyWideOnesAreReadable() throws Exception {
        Person hr = person(hrRole, "hr"), employee = person(employeeRole, "emp");
        String secret = project(hr.cookie(), "SEC", "PRIVATE"), open = project(hr.cookie(), "OPN", "BUSINESS");
        JsonNode list = getData(employee.cookie(), "/api/v1/projects");
        assertThat(list.get("total").asInt()).isEqualTo(1);
        assertThat(list.get("rows").get(0).get("key").asText()).isEqualTo("OPN");
        assertThat(list.get("rows").get(0).get("myRole").isNull()).isTrue();
        mockMvc.perform(get("/api/v1/projects/" + secret).cookie(employee.cookie())).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/projects/" + secret + "/members").cookie(employee.cookie())).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/projects/" + secret + "/board").cookie(employee.cookie())).andExpect(status().isNotFound());
        JsonNode detail = getData(employee.cookie(), "/api/v1/projects/" + open);
        assertThat(detail.get("canManage").asBoolean()).isFalse();
        assertThat(detail.get("canCreateTasks").asBoolean()).isFalse();
        // a non-member can't change a company-wide project
        mockMvc.perform(write(put("/api/v1/projects/" + open + "/status")).cookie(employee.cookie()).content("{\"status\":\"ARCHIVED\"}")).andExpect(status().isForbidden());
        // search and sorting
        assertThat(getData(hr.cookie(), "/api/v1/projects?search=sec").get("total").asInt()).isEqualTo(1);
        assertThat(getData(hr.cookie(), "/api/v1/projects?sort=key&sortDirection=desc").get("rows").get(0).get("key").asText()).isEqualTo("SEC");
    }

    @Test
    void membersKeepAtLeastOneOwnerAndCanLeave() throws Exception {
        Person hr = person(hrRole, "hr"), employee = person(employeeRole, "emp"), other = person(employeeRole, "other");
        String projectId = project(hr.cookie(), "MEM", "PRIVATE");
        addMember(hr.cookie(), projectId, employee, "MEMBER");
        mockMvc.perform(write(post("/api/v1/projects/" + projectId + "/members")).cookie(hr.cookie()).content("{\"employeeId\":\"" + employee.id() + "\",\"role\":\"VIEWER\"}"))
                .andExpect(status().isConflict());
        mockMvc.perform(write(post("/api/v1/projects/" + projectId + "/members")).cookie(hr.cookie()).content("{\"employeeId\":\"" + java.util.UUID.randomUUID() + "\"}"))
                .andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE employee_id = ? AND text LIKE 'You were added to the project%'", Integer.class,
                employee.id())).isEqualTo(1);
        // a member can't manage members
        mockMvc.perform(write(post("/api/v1/projects/" + projectId + "/members")).cookie(employee.cookie()).content("{\"employeeId\":\"" + other.id() + "\"}"))
                .andExpect(status().isForbidden());
        // the last owner can't step down or leave
        mockMvc.perform(write(put("/api/v1/projects/" + projectId + "/members/" + hr.id())).cookie(hr.cookie()).content("{\"role\":\"MEMBER\"}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.detail").value("A project needs at least one owner."));
        mockMvc.perform(write(delete("/api/v1/projects/" + projectId + "/members/" + hr.id())).cookie(hr.cookie())).andExpect(status().isUnprocessableEntity());
        // with a second owner it works
        mockMvc.perform(write(put("/api/v1/projects/" + projectId + "/members/" + employee.id())).cookie(hr.cookie()).content("{\"role\":\"OWNER\"}")).andExpect(status().isOk());
        mockMvc.perform(write(delete("/api/v1/projects/" + projectId + "/members/" + hr.id())).cookie(hr.cookie())).andExpect(status().isOk());
        JsonNode members = getData(employee.cookie(), "/api/v1/projects/" + projectId + "/members");
        assertThat(members.size()).isEqualTo(1);
        assertThat(members.get(0).get("role").asText()).isEqualTo("OWNER");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE entity_id = ? AND action LIKE 'project.member_%'", Integer.class, projectId)).isEqualTo(3);
    }

    @Test
    void workflowStepsFollowTheRules() throws Exception {
        Person hr = person(hrRole, "hr");
        String projectId = project(hr.cookie(), "WF", "PRIVATE");
        String base = "/api/v1/projects/" + projectId;
        String blocked = postCreated(hr.cookie(), base + "/workflow-statuses", "{\"name\":\"Blocked\",\"category\":\"IN_PROGRESS\",\"color\":\"red\"}").get("id").asText();
        mockMvc.perform(write(post(base + "/workflow-statuses")).cookie(hr.cookie()).content("{\"name\":\"blocked\",\"category\":\"TODO\"}")).andExpect(status().isConflict());
        mockMvc.perform(write(post(base + "/workflow-statuses")).cookie(hr.cookie()).content("{\"name\":\"Odd\",\"category\":\"LATER\"}")).andExpect(status().isBadRequest());
        // a task in "Blocked": deleting the step needs a target
        JsonNode task = task(hr.cookie(), "\"projectId\":\"" + projectId + "\",\"title\":\"Stuck\",\"statusId\":\"" + blocked + "\"");
        mockMvc.perform(write(delete(base + "/workflow-statuses/" + blocked)).cookie(hr.cookie())).andExpect(status().isUnprocessableEntity());
        String done = statusId(projectId, "Done");
        mockMvc.perform(write(delete(base + "/workflow-statuses/" + blocked + "?moveTasksToStatusId=" + done)).cookie(hr.cookie())).andExpect(status().isOk());
        JsonNode moved = getData(hr.cookie(), "/api/v1/tasks/" + task.get("id").asText());
        assertThat(moved.get("statusName").asText()).isEqualTo("Done");
        assertThat(moved.get("completedAt").isNull()).isFalse(); // moved into a done step
        // the last done step and the starting step stay
        mockMvc.perform(write(delete(base + "/workflow-statuses/" + statusId(projectId, "Cancelled"))).cookie(hr.cookie())).andExpect(status().isOk());
        mockMvc.perform(write(delete(base + "/workflow-statuses/" + statusId(projectId, "Done") + "?moveTasksToStatusId=" + statusId(projectId, "To do"))).cookie(hr.cookie()))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.detail").value("A workflow needs at least one done step."));
        mockMvc.perform(write(delete(base + "/workflow-statuses/" + statusId(projectId, "To do"))).cookie(hr.cookie())).andExpect(status().isUnprocessableEntity());
        // a done step can't be the starting one
        mockMvc.perform(write(put(base + "/workflow-statuses/" + done)).cookie(hr.cookie()).content("{\"name\":\"Done\",\"category\":\"DONE\",\"isInitial\":true}"))
                .andExpect(status().isUnprocessableEntity());
        // renaming and moving the start works
        String review = statusId(projectId, "In review");
        mockMvc.perform(write(put(base + "/workflow-statuses/" + review)).cookie(hr.cookie()).content("{\"name\":\"Ready\",\"category\":\"TODO\",\"color\":\"amber\",\"isInitial\":true}"))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT name FROM task_statuses WHERE project_id = ?::uuid AND is_initial", String.class, projectId)).isEqualTo("Ready");
        // order: every step once
        List<String> ids = jdbc.queryForList("SELECT id::text FROM task_statuses WHERE project_id = ?::uuid ORDER BY position DESC", String.class, projectId);
        mockMvc.perform(write(put(base + "/workflow-status-order")).cookie(hr.cookie()).content("{\"statusIds\":[\"" + ids.get(0) + "\"]}")).andExpect(status().isBadRequest());
        mockMvc.perform(write(put(base + "/workflow-status-order")).cookie(hr.cookie()).content("{\"statusIds\":[\"" + String.join("\",\"", ids) + "\"]}"))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT id::text FROM task_statuses WHERE project_id = ?::uuid ORDER BY position LIMIT 1", String.class, projectId)).isEqualTo(ids.get(0));
        // at most 12 steps
        int count = ids.size();
        for (int i = count; i < 12; i++) postCreated(hr.cookie(), base + "/workflow-statuses", "{\"name\":\"Step " + i + "\",\"category\":\"IN_PROGRESS\"}");
        mockMvc.perform(write(post(base + "/workflow-statuses")).cookie(hr.cookie()).content("{\"name\":\"One too many\",\"category\":\"IN_PROGRESS\"}"))
                .andExpect(status().isUnprocessableEntity());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action LIKE 'workflow_status.%' AND (after->>'projectId' = ? OR before->>'projectId' = ?)",
                Integer.class, projectId, projectId)).isGreaterThan(3);
    }

    @Test
    void archivedProjectsAreReadOnlyAndOnlyEmptyProjectsAreDeleted() throws Exception {
        Person hr = person(hrRole, "hr");
        String projectId = project(hr.cookie(), "ARC", "PRIVATE");
        String empty = project(hr.cookie(), "EMP", "PRIVATE");
        task(hr.cookie(), "\"projectId\":\"" + projectId + "\",\"title\":\"Keep me\"");
        mockMvc.perform(write(delete("/api/v1/projects/" + projectId)).cookie(hr.cookie())).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("This project has tasks. Archive it instead."));
        mockMvc.perform(write(put("/api/v1/projects/" + projectId + "/status")).cookie(hr.cookie()).content("{\"status\":\"ARCHIVED\"}")).andExpect(status().isOk());
        mockMvc.perform(write(post("/api/v1/tasks")).cookie(hr.cookie()).content("{\"projectId\":\"" + projectId + "\",\"title\":\"Late\"}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.detail").value("This project is archived. Restore it to make changes."));
        mockMvc.perform(write(put("/api/v1/projects/" + projectId)).cookie(hr.cookie()).content("{\"version\":0,\"key\":\"ARC\",\"name\":\"Renamed\"}"))
                .andExpect(status().isUnprocessableEntity());
        assertThat(getData(hr.cookie(), "/api/v1/projects").get("total").asInt()).isEqualTo(1);
        assertThat(getData(hr.cookie(), "/api/v1/projects?status=ARCHIVED").get("total").asInt()).isEqualTo(1);
        mockMvc.perform(write(put("/api/v1/projects/" + projectId + "/status")).cookie(hr.cookie()).content("{\"status\":\"ACTIVE\"}")).andExpect(status().isOk());
        assertThat(auditRows("project.archive", projectId)).isEqualTo(1);
        assertThat(auditRows("project.restore", projectId)).isEqualTo(1);
        // an empty project is soft-deleted: gone from the API, still in the database
        mockMvc.perform(write(delete("/api/v1/projects/" + empty)).cookie(hr.cookie())).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/projects/" + empty).cookie(hr.cookie())).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT deleted_at IS NOT NULL FROM projects WHERE id = ?::uuid", Boolean.class, empty)).isTrue();
    }

    @Test
    void editsNeedTheCurrentVersionAndKeysFreezeOnceThereAreTasks() throws Exception {
        Person hr = person(hrRole, "hr");
        String projectId = project(hr.cookie(), "VER", "PRIVATE");
        JsonNode saved = read(mockMvc.perform(write(put("/api/v1/projects/" + projectId)).cookie(hr.cookie())
                .content("{\"version\":0,\"key\":\"VRS\",\"name\":\"Versioned\",\"description\":\"About\",\"visibility\":\"BUSINESS\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(saved.at("/data/version").asInt()).isEqualTo(1);
        mockMvc.perform(write(put("/api/v1/projects/" + projectId)).cookie(hr.cookie()).content("{\"version\":0,\"key\":\"VRS\",\"name\":\"Stale\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONFLICT"));
        task(hr.cookie(), "\"projectId\":\"" + projectId + "\",\"title\":\"First\"");
        mockMvc.perform(write(put("/api/v1/projects/" + projectId)).cookie(hr.cookie()).content("{\"version\":1,\"key\":\"NEW\",\"name\":\"Versioned\"}"))
                .andExpect(status().isUnprocessableEntity());
        assertThat(auditRows("project.update", projectId)).isEqualTo(1);
    }
}
