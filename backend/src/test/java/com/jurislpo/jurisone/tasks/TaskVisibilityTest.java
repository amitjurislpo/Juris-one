package com.jurislpo.jurisone.tasks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

/** Who sees which task (plan §4.3, §4.4): 404 for what you can't see, the Owner sees everything (audited), field-level rights. */
class TaskVisibilityTest extends TaskApiTestBase {

    @Test
    void aPrivateProjectsTasksAreNotFoundForOutsiders() throws Exception {
        Person hr = person(hrRole, "hr"), outsider = person(employeeRole, "outsider");
        String projectId = project(hr.cookie(), "PRV", "PRIVATE");
        String id = task(hr.cookie(), "\"projectId\":\"" + projectId + "\",\"title\":\"Secret\"").get("id").asText();
        mockMvc.perform(get("/api/tasks/" + id).cookie(outsider.cookie())).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/tasks/PRV-1").cookie(outsider.cookie())).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/tasks/" + id + "/activity").cookie(outsider.cookie())).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/tasks/" + id + "/comments").cookie(outsider.cookie())).andExpect(status().isNotFound());
        mockMvc.perform(write(patch("/api/tasks/" + id)).cookie(outsider.cookie()).content("{\"version\":0,\"title\":\"Hacked\"}")).andExpect(status().isNotFound());
        mockMvc.perform(write(delete("/api/tasks/" + id)).cookie(outsider.cookie())).andExpect(status().isNotFound());
        mockMvc.perform(write(put("/api/tasks/" + id + "/watchers/" + outsider.id())).cookie(outsider.cookie())).andExpect(status().isNotFound());
        assertThat(getData(outsider.cookie(), "/api/tasks").get("total").asInt()).isZero();
        assertThat(getData(outsider.cookie(), "/api/tasks?search=secret").get("total").asInt()).isZero();
    }

    @Test
    void aCompanyWideProjectIsReadAndCommentForNonMembers() throws Exception {
        Person hr = person(hrRole, "hr"), colleague = person(employeeRole, "colleague");
        String projectId = project(hr.cookie(), "PUB", "BUSINESS");
        String id = task(hr.cookie(), "\"projectId\":\"" + projectId + "\",\"title\":\"Open\"").get("id").asText();
        JsonNode permissions = getData(colleague.cookie(), "/api/tasks/" + id).get("permissions");
        assertThat(permissions.get("canComment").asBoolean()).isTrue();
        assertThat(permissions.get("canEdit").asBoolean()).isFalse();
        mockMvc.perform(write(patch("/api/tasks/" + id)).cookie(colleague.cookie()).content("{\"version\":0,\"title\":\"Mine now\"}")).andExpect(status().isForbidden());
        mockMvc.perform(write(patch("/api/tasks/" + id)).cookie(colleague.cookie()).content("{\"version\":0,\"priority\":\"HIGH\"}")).andExpect(status().isForbidden());
        // sending a field unchanged is not a change
        mockMvc.perform(write(patch("/api/tasks/" + id)).cookie(colleague.cookie()).content("{\"version\":0,\"title\":\"Open\"}")).andExpect(status().isOk());
        assertThat(getData(colleague.cookie(), "/api/tasks").get("total").asInt()).isEqualTo(1);
        // non-members can't add tasks to it
        mockMvc.perform(write(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/tasks")).cookie(colleague.cookie())
                .content("{\"projectId\":\"" + projectId + "\",\"title\":\"Mine\"}")).andExpect(status().isForbidden());
    }

    @Test
    void theOwnerSeesEverythingAndPrivateAccessIsAudited() throws Exception {
        Person owner = person(ownerRole, "owner"), hr = person(hrRole, "hr"), employee = person(employeeRole, "emp");
        String projectId = project(hr.cookie(), "OWN", "PRIVATE");
        String id = task(hr.cookie(), "\"projectId\":\"" + projectId + "\",\"title\":\"Payroll check\"").get("id").asText();
        String personal = task(employee.cookie(), "\"title\":\"My own to-do\"").get("id").asText();
        assertThat(getData(owner.cookie(), "/api/projects").get("total").asInt()).isEqualTo(1);
        JsonNode project = getData(owner.cookie(), "/api/projects/" + projectId);
        assertThat(project.get("canManage").asBoolean()).isTrue();
        assertThat(auditRows("project.private_access", projectId)).isEqualTo(1);
        JsonNode task = getData(owner.cookie(), "/api/tasks/" + id);
        assertThat(task.get("permissions").get("canDelete").asBoolean()).isTrue();
        assertThat(auditRows("task.private_access", id)).isEqualTo(1);
        // a personal task: the Owner reads (audited) but does not edit
        JsonNode personalTask = getData(owner.cookie(), "/api/tasks/" + personal);
        assertThat(personalTask.get("permissions").get("canEdit").asBoolean()).isFalse();
        assertThat(auditRows("task.private_access", personal)).isEqualTo(1);
        assertThat(getData(owner.cookie(), "/api/tasks").get("total").asInt()).isEqualTo(2);
        // members' own reads are not "private access"
        getData(hr.cookie(), "/api/tasks/" + id);
        assertThat(auditRows("task.private_access", id)).isEqualTo(1);
        // nobody else sees the personal task
        mockMvc.perform(get("/api/tasks/" + personal).cookie(hr.cookie())).andExpect(status().isNotFound());
        // the audit log has a "tasks" event group
        JsonNode audit = getData(owner.cookie(), "/api/audit?eventGroup=tasks&periodDays=7");
        assertThat(audit.get("rows").findValuesAsText("action")).contains("project.create", "task.create", "task.private_access", "project.private_access")
                .allMatch(action -> action.startsWith("project.") || action.startsWith("task.") || action.startsWith("workflow_status.") || action.startsWith("task_label."));
    }

    @Test
    void theIndirectAssigneeChangesStatusPriorityAndAssigneeButNothingElse() throws Exception {
        Person hr = person(hrRole, "hr"), doer = person(employeeRole, "doer"), accountable = person(employeeRole, "accountable"), other = person(employeeRole, "other");
        String projectId = project(hr.cookie(), "IND", "PRIVATE");
        String id = task(hr.cookie(), "\"projectId\":\"" + projectId + "\",\"title\":\"Review\",\"assigneeId\":\"" + doer.id() + "\",\"indirectAssigneeId\":\"" + accountable.id() + "\"")
                .get("id").asText();
        JsonNode permissions = getData(accountable.cookie(), "/api/tasks/" + id).get("permissions");
        assertThat(permissions.get("canChangeStatus").asBoolean()).isTrue();
        assertThat(permissions.get("canEdit").asBoolean()).isFalse();
        int version = version(accountable.cookie(), id);
        mockMvc.perform(write(patch("/api/tasks/" + id)).cookie(accountable.cookie()).content("{\"version\":" + version + ",\"statusCategory\":\"IN_PROGRESS\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(write(patch("/api/tasks/" + id)).cookie(accountable.cookie()).content("{\"version\":" + (version + 1) + ",\"priority\":\"HIGH\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(write(patch("/api/tasks/" + id)).cookie(accountable.cookie()).content("{\"version\":" + (version + 2) + ",\"assigneeId\":\"" + other.id() + "\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(write(patch("/api/tasks/" + id)).cookie(accountable.cookie()).content("{\"version\":" + (version + 3) + ",\"title\":\"Renamed\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(write(patch("/api/tasks/" + id)).cookie(accountable.cookie()).content("{\"version\":" + (version + 3) + ",\"dueDate\":\"2031-01-01\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(write(delete("/api/tasks/" + id)).cookie(accountable.cookie())).andExpect(status().isForbidden());
        // the new direct assignee joined the project; the accountable person did not
        assertThat(jdbc.queryForObject("SELECT count(*) FROM project_members WHERE project_id = ?::uuid AND employee_id = ?", Integer.class, projectId, other.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM project_members WHERE project_id = ?::uuid AND employee_id = ?", Integer.class, projectId, accountable.id())).isZero();
        // they may step down themselves
        mockMvc.perform(write(patch("/api/tasks/" + id)).cookie(accountable.cookie()).content("{\"version\":" + (version + 3) + ",\"indirectAssigneeId\":null}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/tasks/" + id).cookie(accountable.cookie())).andExpect(status().isOk()); // still a follower
        // the first direct assignee, now a member, can still read it
        assertThat(getData(doer.cookie(), "/api/tasks/" + id).get("assigneeId").asText()).isEqualTo(other.id().toString());
    }

    @Test
    void managersReadTheirReportsTasksTwoLevelsDown() throws Exception {
        Person hr = person(hrRole, "hr"), top = person(employeeRole, "top"), middle = person(employeeRole, "middle"), worker = person(employeeRole, "worker"),
                boss = person(employeeRole, "boss");
        reportsTo(worker, middle);
        reportsTo(middle, top);
        reportsTo(top, boss);
        String projectId = project(hr.cookie(), "MGR", "PRIVATE");
        String id = task(hr.cookie(), "\"projectId\":\"" + projectId + "\",\"title\":\"Deep work\",\"assigneeId\":\"" + worker.id() + "\"").get("id").asText();
        JsonNode seenByMiddle = getData(middle.cookie(), "/api/tasks/" + id);
        assertThat(seenByMiddle.get("permissions").get("canComment").asBoolean()).isTrue();
        assertThat(seenByMiddle.get("permissions").get("canEdit").asBoolean()).isFalse();
        getData(top.cookie(), "/api/tasks/" + id); // depth 2
        mockMvc.perform(get("/api/tasks/" + id).cookie(boss.cookie())).andExpect(status().isNotFound()); // depth 3
        mockMvc.perform(write(patch("/api/tasks/" + id)).cookie(middle.cookie()).content("{\"version\":0,\"priority\":\"LOW\"}")).andExpect(status().isForbidden());
        // only the task, not the rest of the project
        mockMvc.perform(get("/api/projects/" + projectId).cookie(middle.cookie())).andExpect(status().isNotFound());
        assertThat(getData(top.cookie(), "/api/tasks").get("total").asInt()).isEqualTo(1);
    }
}
