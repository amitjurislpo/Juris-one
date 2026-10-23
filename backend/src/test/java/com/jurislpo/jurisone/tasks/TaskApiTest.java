package com.jurislpo.jurisone.tasks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.jurislpo.jurisone.engine.TaskDueDates;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Tasks: create, change, order, delete, labels, followers, checklist, My tasks (plan §12.1 T1–T14). */
class TaskApiTest extends TaskApiTestBase {

    @Test
    void tasksAreNumberedPerProjectAndPersonalTasksHaveNoKey() throws Exception {
        Person hr = person(hrRole, "hr");
        String projectId = project(hr.cookie(), "HR", "PRIVATE");
        JsonNode first = task(hr.cookie(), "\"projectId\":\"" + projectId + "\",\"title\":\"Onboarding paperwork\"");
        JsonNode second = task(hr.cookie(), "\"projectId\":\"" + projectId + "\",\"title\":\"Policy refresh\",\"type\":\"ISSUE\",\"priority\":\"HIGH\"");
        assertThat(first.get("key").asText()).isEqualTo("HR-1");
        assertThat(second.get("key").asText()).isEqualTo("HR-2");
        JsonNode personal = task(hr.cookie(), "\"title\":\"Call the bank\"");
        assertThat(personal.get("key").isNull()).isTrue();
        JsonNode detail = getData(hr.cookie(), "/api/tasks/" + personal.get("id").asText());
        assertThat(detail.get("statusName").asText()).isEqualTo("To do"); // the business default set was created on the first write
        assertThat(detail.get("permissions").get("canDelete").asBoolean()).isTrue();
        // lookup by key, case-insensitive
        assertThat(getData(hr.cookie(), "/api/tasks/hr-2").get("title").asText()).isEqualTo("Policy refresh");
        mockMvc.perform(get("/api/tasks/HR-99").cookie(hr.cookie())).andExpect(status().isNotFound());
        // input checks
        mockMvc.perform(write(post("/api/tasks")).cookie(hr.cookie()).content("{\"title\":\"  \"}")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Enter a title."));
        mockMvc.perform(write(post("/api/tasks")).cookie(hr.cookie()).content("{\"title\":\"Timed\",\"dueTime\":\"17:00\"}")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Choose a due date first."));
        mockMvc.perform(write(post("/api/tasks")).cookie(hr.cookie()).content("{\"title\":\"Timed\",\"dueDate\":\"2031-01-10\",\"dueTime\":\"25:00\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(write(post("/api/tasks")).cookie(hr.cookie()).content("{\"title\":\"Dates\",\"startDate\":\"2031-01-10\",\"dueDate\":\"2031-01-09\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(write(post("/api/tasks")).cookie(hr.cookie()).content("{\"title\":\"Big\",\"estimateMinutes\":100001}")).andExpect(status().isBadRequest());
        mockMvc.perform(write(post("/api/tasks")).cookie(hr.cookie()).content("{\"title\":\"Odd\",\"type\":\"EPIC\"}")).andExpect(status().isBadRequest());
        assertThat(auditRows("task.create", first.get("id").asText())).isEqualTo(1);
    }

    @Test
    void bothAssigneesMustDifferAndOnlyTheDirectAssigneeJoinsTheProject() throws Exception {
        Person hr = person(hrRole, "hr"), doer = person(employeeRole, "doer"), reviewer = person(employeeRole, "reviewer");
        String projectId = project(hr.cookie(), "AS", "PRIVATE");
        mockMvc.perform(write(post("/api/tasks")).cookie(hr.cookie())
                        .content("{\"projectId\":\"" + projectId + "\",\"title\":\"Same\",\"assigneeId\":\"" + doer.id() + "\",\"indirectAssigneeId\":\"" + doer.id() + "\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").value("The accountable person must be someone other than the assignee."));
        JsonNode created = task(hr.cookie(), "\"projectId\":\"" + projectId + "\",\"title\":\"Both\",\"assigneeId\":\"" + doer.id() + "\",\"indirectAssigneeId\":\""
                + reviewer.id() + "\",\"dueDate\":\"2031-03-01\",\"dueTime\":\"17:30\"");
        List<String> members = new ArrayList<>();
        getData(hr.cookie(), "/api/projects/" + projectId + "/members").forEach(m -> members.add(m.get("employeeId").asText()));
        assertThat(members).contains(doer.id().toString()).doesNotContain(reviewer.id().toString());
        JsonNode detail = getData(doer.cookie(), "/api/tasks/" + created.get("id").asText());
        assertThat(detail.get("myAssignment").asText()).isEqualTo("DIRECT");
        assertThat(detail.get("dueTime").asText()).isEqualTo("17:30");
        assertThat(detail.get("dueTimeZone").asText()).isEqualTo("Asia/Kolkata");
        assertThat(detail.get("indirectAssigneeName").asText()).startsWith("Person");
        assertThat(detail.get("watchers").size()).isEqualTo(3); // reporter and both assignees follow it
        assertThat(getData(reviewer.cookie(), "/api/tasks/" + created.get("id").asText()).get("myAssignment").asText()).isEqualTo("INDIRECT");
        // both are told
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE employee_id = ? AND text LIKE '%assigned you AS-1%'", Integer.class, doer.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE employee_id = ? AND text LIKE '%made you accountable for AS-1%'", Integer.class,
                reviewer.id())).isEqualTo(1);
        // My tasks: direct, indirect or either
        assertThat(getData(doer.cookie(), "/api/tasks/mine").get("total").asInt()).isEqualTo(1);
        assertThat(getData(reviewer.cookie(), "/api/tasks/mine").get("total").asInt()).isEqualTo(1);
        assertThat(getData(reviewer.cookie(), "/api/tasks/mine?relation=DIRECT").get("total").asInt()).isZero();
        assertThat(getData(reviewer.cookie(), "/api/tasks/mine?relation=INDIRECT").get("total").asInt()).isEqualTo(1);
        assertThat(getData(hr.cookie(), "/api/tasks/mine?relation=REPORTED").get("total").asInt()).isEqualTo(1);
        assertThat(getData(hr.cookie(), "/api/tasks/mine?relation=WATCHING").get("total").asInt()).isEqualTo(1);
        JsonNode mine = getData(doer.cookie(), "/api/tasks/mine");
        assertThat(mine.get("counts").get("later").asInt()).isEqualTo(1);
        assertThat(mine.get("rows").get(0).get("labels").isArray()).isTrue();
        assertThat(mine.get("rows").get(0).get("version").asInt()).isZero(); // list rows carry the version for one-click writes
        // the people picker suggests the assignee's reporting manager as the accountable person
        reportsTo(doer, reviewer);
        JsonNode pickers = getData(hr.cookie(), "/api/tasks/assignees?projectId=" + projectId + "&search=doe-" + suffix);
        JsonNode doerRow = null;
        for (JsonNode row : pickers) if (row.get("id").asText().equals(doer.id().toString())) doerRow = row;
        assertThat(doerRow).isNotNull();
        assertThat(doerRow.get("isMember").asBoolean()).isTrue();
        assertThat(doerRow.get("reportingManagerId").asText()).isEqualTo(reviewer.id().toString());
        assertThat(doerRow.get("reportingManagerName").asText()).isEqualTo(getData(reviewer.cookie(), "/api/tasks/" + created.get("id").asText()).get("indirectAssigneeName").asText());
    }

    @Test
    void patchNeedsTheVersionAndNullClears() throws Exception {
        Person hr = person(hrRole, "hr");
        String projectId = project(hr.cookie(), "PT", "PRIVATE");
        String id = task(hr.cookie(), "\"projectId\":\"" + projectId + "\",\"title\":\"Draft\",\"dueDate\":\"2031-02-01\",\"dueTime\":\"10:00\"").get("id").asText();
        mockMvc.perform(write(patch("/api/tasks/" + id)).cookie(hr.cookie()).content("{\"title\":\"No version\"}")).andExpect(status().isBadRequest());
        JsonNode saved = read(mockMvc.perform(write(patch("/api/tasks/" + id)).cookie(hr.cookie()).content("{\"version\":0,\"title\":\"Final\",\"priority\":\"URGENT\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(saved.at("/data/version").asInt()).isEqualTo(1);
        mockMvc.perform(write(patch("/api/tasks/" + id)).cookie(hr.cookie()).content("{\"version\":0,\"title\":\"Stale\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.detail").value("Someone else changed this task. Reload to see their changes."));
        // clearing the due date clears the time too
        mockMvc.perform(write(patch("/api/tasks/" + id)).cookie(hr.cookie()).content("{\"version\":1,\"dueDate\":null}")).andExpect(status().isOk());
        JsonNode detail = getData(hr.cookie(), "/api/tasks/" + id);
        assertThat(detail.get("dueDate").isNull()).isTrue();
        assertThat(detail.get("dueTime").isNull()).isTrue();
        assertThat(detail.get("title").asText()).isEqualTo("Final");
        // done sets completedAt, back to to-do clears it; statusCategory picks the first step of that category
        mockMvc.perform(write(patch("/api/tasks/" + id)).cookie(hr.cookie()).content("{\"version\":2,\"statusCategory\":\"DONE\"}")).andExpect(status().isOk());
        detail = getData(hr.cookie(), "/api/tasks/" + id);
        assertThat(detail.get("statusName").asText()).isEqualTo("Done");
        assertThat(detail.get("completedAt").isNull()).isFalse();
        mockMvc.perform(write(patch("/api/tasks/" + id)).cookie(hr.cookie()).content("{\"version\":3,\"statusId\":\"" + statusId(projectId, "To do") + "\"}"))
                .andExpect(status().isOk());
        assertThat(getData(hr.cookie(), "/api/tasks/" + id).get("completedAt").isNull()).isTrue();
        mockMvc.perform(write(patch("/api/tasks/" + id)).cookie(hr.cookie()).content("{\"version\":4,\"statusId\":\"" + statusId(projectId, "Done") + "\",\"statusCategory\":\"DONE\"}"))
                .andExpect(status().isBadRequest());
        // the history lists every change, newest first
        JsonNode history = getData(hr.cookie(), "/api/tasks/" + id + "/activity");
        List<String> kinds = new ArrayList<>();
        history.get("rows").forEach(row -> kinds.add(row.get("kind").asText()));
        assertThat(kinds).containsExactly("STATUS_CHANGED", "STATUS_CHANGED", "DATES_CHANGED", "PRIORITY_CHANGED", "TITLE_CHANGED", "CREATED");
        assertThat(history.get("rows").get(0).get("after").get("statusName").asText()).isEqualTo("To do");
        assertThat(auditRows("task.update", id)).isEqualTo(4);
    }

    @Test
    void cardsAreOrderedByTheServerAndColumnsRenumberWhenFull() throws Exception {
        Person hr = person(hrRole, "hr");
        String projectId = project(hr.cookie(), "BRD", "PRIVATE");
        String todo = statusId(projectId, "To do"), doing = statusId(projectId, "In progress");
        String a = task(hr.cookie(), "\"projectId\":\"" + projectId + "\",\"title\":\"A\"").get("id").asText();
        String b = task(hr.cookie(), "\"projectId\":\"" + projectId + "\",\"title\":\"B\"").get("id").asText();
        String c = task(hr.cookie(), "\"projectId\":\"" + projectId + "\",\"title\":\"C\"").get("id").asText();
        // C between A and B
        mockMvc.perform(write(put("/api/tasks/" + c + "/position")).cookie(hr.cookie())
                .content("{\"statusId\":\"" + todo + "\",\"previousTaskId\":\"" + a + "\",\"nextTaskId\":\"" + b + "\"}")).andExpect(status().isOk());
        assertThat(columnTitles(projectId, "To do")).containsExactly("A", "C", "B");
        // no room: A and C sit next to each other, B dropped between them renumbers the column
        jdbc.update("UPDATE tasks SET position = 10 WHERE id = ?::uuid", a);
        jdbc.update("UPDATE tasks SET position = 11 WHERE id = ?::uuid", c);
        mockMvc.perform(write(put("/api/tasks/" + b + "/position")).cookie(hr.cookie())
                .content("{\"statusId\":\"" + todo + "\",\"previousTaskId\":\"" + a + "\",\"nextTaskId\":\"" + c + "\"}")).andExpect(status().isOk());
        assertThat(columnTitles(projectId, "To do")).containsExactly("A", "B", "C");
        // to another column (a status change, written to the history), at the top
        mockMvc.perform(write(put("/api/tasks/" + a + "/position")).cookie(hr.cookie()).content("{\"statusId\":\"" + doing + "\"}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.statusId").value(doing));
        assertThat(getData(hr.cookie(), "/api/tasks/" + a).get("statusName").asText()).isEqualTo("In progress");
        // a neighbour from another column is refused
        mockMvc.perform(write(put("/api/tasks/" + b + "/position")).cookie(hr.cookie())
                .content("{\"statusId\":\"" + todo + "\",\"previousTaskId\":\"" + a + "\"}")).andExpect(status().isBadRequest());
        // the board shows every step with its cards in order
        JsonNode board = getData(hr.cookie(), "/api/projects/" + projectId + "/board");
        assertThat(board.get("columns").size()).isEqualTo(5);
        assertThat(board.get("columns").get(0).get("taskCount").asInt()).isEqualTo(2);
        assertThat(board.get("columns").get(0).get("tasks").get(0).get("title").asText()).isEqualTo("B");
        assertThat(board.get("columns").get(1).get("tasks").get(0).get("key").asText()).isEqualTo("BRD-1");
        assertThat(board.get("project").get("key").asText()).isEqualTo("BRD");
        assertThat(board.get("hasMoreDone").asBoolean()).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'task.move' AND entity_id IN (?, ?, ?)", Integer.class, a, b, c)).isEqualTo(3);
    }

    List<String> columnTitles(String projectId, String statusName) {
        return jdbc.queryForList("SELECT t.title FROM tasks t JOIN task_statuses s ON s.id = t.status_id WHERE t.project_id = ?::uuid AND s.name = ? AND t.deleted_at IS NULL "
                + "ORDER BY t.position", String.class, projectId, statusName);
    }

    @Test
    void subTasksAreOneLevelDeepInTheSameProject() throws Exception {
        Person hr = person(hrRole, "hr");
        String projectId = project(hr.cookie(), "SUB", "PRIVATE"), otherProject = project(hr.cookie(), "OTH", "PRIVATE");
        String parent = task(hr.cookie(), "\"projectId\":\"" + projectId + "\",\"title\":\"Parent\"").get("id").asText();
        String child = task(hr.cookie(), "\"projectId\":\"" + projectId + "\",\"title\":\"Child\",\"parentTaskId\":\"" + parent + "\"").get("id").asText();
        mockMvc.perform(write(post("/api/tasks")).cookie(hr.cookie()).content("{\"projectId\":\"" + projectId + "\",\"title\":\"Grandchild\",\"parentTaskId\":\"" + child + "\"}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.detail").value("A sub-task can't have its own sub-tasks."));
        mockMvc.perform(write(post("/api/tasks")).cookie(hr.cookie()).content("{\"projectId\":\"" + otherProject + "\",\"title\":\"Elsewhere\",\"parentTaskId\":\"" + parent + "\"}"))
                .andExpect(status().isUnprocessableEntity());
        JsonNode detail = getData(hr.cookie(), "/api/tasks/" + parent);
        assertThat(detail.get("subtaskCount").asInt()).isEqualTo(1);
        assertThat(detail.get("subtasks").get(0).get("parentKey").asText()).isEqualTo("SUB-1");
        // a task with sub-tasks can't become a sub-task
        String other = task(hr.cookie(), "\"projectId\":\"" + projectId + "\",\"title\":\"Other\"").get("id").asText();
        mockMvc.perform(write(patch("/api/tasks/" + parent)).cookie(hr.cookie()).content("{\"version\":0,\"parentTaskId\":\"" + other + "\"}"))
                .andExpect(status().isUnprocessableEntity());
        // deleting the parent soft-deletes its sub-tasks: gone from the API, kept in the database
        mockMvc.perform(write(delete("/api/tasks/" + parent)).cookie(hr.cookie())).andExpect(status().isOk());
        mockMvc.perform(get("/api/tasks/" + parent).cookie(hr.cookie())).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/tasks/" + child).cookie(hr.cookie())).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tasks WHERE id IN (?::uuid, ?::uuid) AND deleted_at IS NOT NULL", Integer.class, parent, child)).isEqualTo(2);
        assertThat(getData(hr.cookie(), "/api/tasks?projectId=" + projectId).get("total").asInt()).isEqualTo(1);
        assertThat(auditRows("task.delete", parent)).isEqualTo(1);
    }

    @Test
    void labelsFollowersAndChecklist() throws Exception {
        Person owner = person(ownerRole, "owner"), hr = person(hrRole, "hr"), employee = person(employeeRole, "emp");
        String projectId = project(hr.cookie(), "LBL", "BUSINESS"), otherProject = project(hr.cookie(), "XLB", "PRIVATE");
        String businessLabel = postCreated(owner.cookie(), "/api/task-labels", "{\"name\":\"Urgent client\",\"color\":\"red\"}").get("id").asText();
        mockMvc.perform(write(post("/api/task-labels")).cookie(hr.cookie()).content("{\"name\":\"Nope\"}")).andExpect(status().isForbidden()); // business labels: tasks.manage
        String projectLabel = postCreated(hr.cookie(), "/api/task-labels", "{\"name\":\"frontend\",\"projectId\":\"" + projectId + "\"}").get("id").asText();
        String foreignLabel = postCreated(hr.cookie(), "/api/task-labels", "{\"name\":\"other\",\"projectId\":\"" + otherProject + "\"}").get("id").asText();
        mockMvc.perform(write(post("/api/task-labels")).cookie(hr.cookie()).content("{\"name\":\"FRONTEND\",\"projectId\":\"" + projectId + "\"}")).andExpect(status().isConflict());
        assertThat(getData(employee.cookie(), "/api/task-labels?projectId=" + projectId).size()).isEqualTo(2);
        mockMvc.perform(get("/api/task-labels?projectId=" + otherProject).cookie(employee.cookie())).andExpect(status().isNotFound());

        String id = task(hr.cookie(), "\"projectId\":\"" + projectId + "\",\"title\":\"Labelled\",\"labelIds\":[\"" + businessLabel + "\"],\"checklist\":[\"Draft\",\"Review\"]")
                .get("id").asText();
        mockMvc.perform(write(patch("/api/tasks/" + id)).cookie(hr.cookie()).content("{\"version\":0,\"labelIds\":[\"" + foreignLabel + "\"]}")).andExpect(status().isBadRequest());
        mockMvc.perform(write(patch("/api/tasks/" + id)).cookie(hr.cookie()).content("{\"version\":0,\"labelIds\":[\"" + businessLabel + "\",\"" + projectLabel + "\"]}"))
                .andExpect(status().isOk());
        JsonNode detail = getData(hr.cookie(), "/api/tasks/" + id);
        assertThat(detail.get("labels").size()).isEqualTo(2);
        assertThat(detail.get("checklist").size()).isEqualTo(2);
        assertThat(detail.get("checklistCount").asInt()).isEqualTo(2);

        // followers: anyone who sees it may follow (idempotent); others are added by those who can edit
        mockMvc.perform(write(put("/api/tasks/" + id + "/watchers/" + employee.id())).cookie(employee.cookie())).andExpect(status().isOk());
        mockMvc.perform(write(put("/api/tasks/" + id + "/watchers/" + employee.id())).cookie(employee.cookie())).andExpect(status().isOk());
        assertThat(getData(employee.cookie(), "/api/tasks/" + id).get("isWatching").asBoolean()).isTrue();
        mockMvc.perform(write(delete("/api/tasks/" + id + "/watchers/" + hr.id())).cookie(employee.cookie())).andExpect(status().isForbidden());
        mockMvc.perform(write(delete("/api/tasks/" + id + "/watchers/" + employee.id())).cookie(employee.cookie())).andExpect(status().isOk());
        assertThat(auditRows("task.watcher_add", id)).isEqualTo(1);

        // checklist: add, tick, move, remove; a viewer can't
        String item = postCreated(hr.cookie(), "/api/tasks/" + id + "/checklist-items", "{\"text\":\"Publish\"}").get("id").asText();
        mockMvc.perform(write(patch("/api/tasks/" + id + "/checklist-items/" + item)).cookie(hr.cookie()).content("{\"isDone\":true,\"position\":1}")).andExpect(status().isOk());
        JsonNode checklist = getData(hr.cookie(), "/api/tasks/" + id).get("checklist");
        assertThat(checklist.get(0).get("text").asText()).isEqualTo("Publish");
        assertThat(checklist.get(0).get("isDone").asBoolean()).isTrue();
        assertThat(checklist.get(0).get("doneByName").asText()).startsWith("Person");
        mockMvc.perform(write(post("/api/tasks/" + id + "/checklist-items")).cookie(employee.cookie()).content("{\"text\":\"Sneak\"}")).andExpect(status().isForbidden());
        mockMvc.perform(write(delete("/api/tasks/" + id + "/checklist-items/" + item)).cookie(hr.cookie())).andExpect(status().isOk());
        mockMvc.perform(write(delete("/api/tasks/" + id + "/checklist-items/" + item)).cookie(hr.cookie())).andExpect(status().isNotFound());
        assertThat(auditRows("task.checklist_change", id)).isEqualTo(3);

        // deleting a label removes it from the task
        mockMvc.perform(write(delete("/api/task-labels/" + projectLabel)).cookie(hr.cookie())).andExpect(status().isOk());
        assertThat(getData(hr.cookie(), "/api/tasks/" + id).get("labels").size()).isEqualTo(1);
        assertThat(getData(hr.cookie(), "/api/tasks?labelId=" + businessLabel).get("total").asInt()).isEqualTo(1);
    }

    @Test
    void theDueFilterInSqlMatchesTheEngineRule() throws Exception {
        Person employee = person(employeeRole, "due");
        jdbc.update("UPDATE employees SET time_zone = 'America/New_York' WHERE id = ?", employee.id());
        LocalDate today = LocalDate.now(ZoneId.of("America/New_York"));
        Map<String, String[]> created = new HashMap<>(); // id -> {dueDate, dueTime}
        for (int offset = -2; offset <= 10; offset++) {
            String due = today.plusDays(offset).toString();
            created.put(task(employee.cookie(), "\"title\":\"Due " + offset + "\",\"assigneeId\":\"" + employee.id() + "\",\"dueDate\":\"" + due + "\"").get("id").asText(),
                    new String[] {due, null});
        }
        for (String time : List.of("00:01", "23:59")) {
            created.put(task(employee.cookie(), "\"title\":\"Timed " + time + "\",\"assigneeId\":\"" + employee.id() + "\",\"dueDate\":\"" + today + "\",\"dueTime\":\"" + time + "\"")
                    .get("id").asText(), new String[] {today.toString(), time});
        }
        created.put(task(employee.cookie(), "\"title\":\"Someday\",\"assigneeId\":\"" + employee.id() + "\"").get("id").asText(), new String[] {null, null});
        Instant now = Instant.now();
        for (String bucket : List.of(TaskDueDates.OVERDUE, TaskDueDates.TODAY, TaskDueDates.THIS_WEEK, TaskDueDates.LATER, TaskDueDates.NO_DATE)) {
            Set<String> expected = new HashSet<>();
            created.forEach((id, due) -> {
                String engine = TaskDueDates.bucket(due[0] == null ? null : LocalDate.parse(due[0]), due[1] == null ? null : LocalTime.parse(due[1]), "America/New_York", now);
                if (engine.equals(bucket)) expected.add(id);
            });
            Set<String> fromSql = new HashSet<>(getData(employee.cookie(), "/api/tasks/mine?pageSize=500&due=" + bucket).get("rows").findValuesAsText("id"));
            assertThat(fromSql).as(bucket).isEqualTo(expected);
        }
        JsonNode counts = getData(employee.cookie(), "/api/tasks/mine").get("counts");
        assertThat(counts.get("overdue").asInt() + counts.get("today").asInt() + counts.get("thisWeek").asInt() + counts.get("later").asInt() + counts.get("noDate").asInt())
                .isEqualTo(created.size());
        // the badge: open tasks overdue or due today
        long dueByToday = created.values().stream().filter(due -> due[0] != null && !LocalDate.parse(due[0]).isAfter(today)).count();
        assertThat(getData(employee.cookie(), "/api/shell").get("badges").get("myTasks").asLong()).isEqualTo(dueByToday);
    }

    @Test
    void movingToAnotherProjectGivesANewKeyAndTheOldOneStillWorks() throws Exception {
        Person hr = person(hrRole, "hr");
        String from = project(hr.cookie(), "OLD", "PRIVATE"), to = project(hr.cookie(), "NEW", "PRIVATE");
        task(hr.cookie(), "\"projectId\":\"" + to + "\",\"title\":\"Already there\"");
        String id = task(hr.cookie(), "\"projectId\":\"" + from + "\",\"title\":\"Mover\"").get("id").asText();
        mockMvc.perform(write(patch("/api/tasks/" + id)).cookie(hr.cookie()).content("{\"version\":0,\"projectId\":\"" + to + "\"}")).andExpect(status().isOk());
        JsonNode moved = getData(hr.cookie(), "/api/tasks/OLD-1");
        assertThat(moved.get("key").asText()).isEqualTo("NEW-2");
        assertThat(moved.get("previousKeys").get(0).asText()).isEqualTo("OLD-1");
        assertThat(moved.get("statusName").asText()).isEqualTo("To do");
        assertThat(getData(hr.cookie(), "/api/tasks?search=OLD-1").get("total").asInt()).isEqualTo(1);
        assertThat(getData(hr.cookie(), "/api/tasks?search=mov").get("total").asInt()).isEqualTo(1); // prefix search
    }

    @Test
    void theExtraReminderIsOneOfTheAllowedOffsetsNeedsADueDateAndIsClearedWithIt() throws Exception {
        Person hr = person(hrRole, "hr");
        // created with a reminder: stored and shown on the task
        String id = task(hr.cookie(), "\"title\":\"Remind me\",\"dueDate\":\"2031-03-01\",\"dueTime\":\"17:30\",\"reminderBeforeMinutes\":60").get("id").asText();
        assertThat(getData(hr.cookie(), "/api/tasks/" + id).get("reminderBeforeMinutes").asInt()).isEqualTo(60);
        String plain = task(hr.cookie(), "\"title\":\"None\",\"dueDate\":\"2031-03-01\"").get("id").asText();
        assertThat(getData(hr.cookie(), "/api/tasks/" + plain).get("reminderBeforeMinutes").isNull()).isTrue();
        // only 15, 60, 240 and 1440 minutes, and only with a due date (problem details)
        mockMvc.perform(write(post("/api/tasks")).cookie(hr.cookie()).content("{\"title\":\"Odd\",\"dueDate\":\"2031-03-01\",\"reminderBeforeMinutes\":30}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").value("Choose 15 minutes, 1 hour, 4 hours or 1 day before the due time."));
        mockMvc.perform(write(post("/api/tasks")).cookie(hr.cookie()).content("{\"title\":\"No date\",\"reminderBeforeMinutes\":15}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").value("Choose a due date first."));
        mockMvc.perform(write(patch("/api/tasks/" + id)).cookie(hr.cookie()).content("{\"version\":0,\"reminderBeforeMinutes\":90}")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Choose 15 minutes, 1 hour, 4 hours or 1 day before the due time."));
        mockMvc.perform(write(patch("/api/tasks/" + id)).cookie(hr.cookie()).content("{\"version\":0,\"reminderBeforeMinutes\":\"60\"}")).andExpect(status().isBadRequest());
        // change it, audited
        mockMvc.perform(write(patch("/api/tasks/" + id)).cookie(hr.cookie()).content("{\"version\":0,\"reminderBeforeMinutes\":1440}")).andExpect(status().isOk());
        assertThat(getData(hr.cookie(), "/api/tasks/" + id).get("reminderBeforeMinutes").asInt()).isEqualTo(1440);
        assertThat(auditRows("task.update", id)).isEqualTo(1);
        // null removes it; clearing the due date removes it too
        mockMvc.perform(write(patch("/api/tasks/" + id)).cookie(hr.cookie()).content("{\"version\":1,\"reminderBeforeMinutes\":null}")).andExpect(status().isOk());
        assertThat(getData(hr.cookie(), "/api/tasks/" + id).get("reminderBeforeMinutes").isNull()).isTrue();
        mockMvc.perform(write(patch("/api/tasks/" + id)).cookie(hr.cookie()).content("{\"version\":2,\"reminderBeforeMinutes\":15}")).andExpect(status().isOk());
        mockMvc.perform(write(patch("/api/tasks/" + id)).cookie(hr.cookie()).content("{\"version\":3,\"dueDate\":null}")).andExpect(status().isOk());
        assertThat(getData(hr.cookie(), "/api/tasks/" + id).get("reminderBeforeMinutes").isNull()).isTrue();
    }
}
