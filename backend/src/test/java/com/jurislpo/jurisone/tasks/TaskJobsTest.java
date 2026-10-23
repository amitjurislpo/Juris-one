package com.jurislpo.jurisone.tasks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jurislpo.jurisone.service.TaskJobs;
import com.jurislpo.jurisone.tenancy.TenantContext;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Due tomorrow / due today / overdue reminders in the bell (plan §14), once per due date, to both assignees, in the task time zone.
 * D = 2031-03-10; India is UTC+05:30, New York UTC-04:00 (daylight time from 9 March 2031).
 */
class TaskJobsTest extends TaskApiTestBase {

    @Autowired TaskJobs taskJobs;

    Map<String, Object> run(String instant) {
        return TenantContext.call(data.business(), () -> taskJobs.run(Instant.parse(instant)));
    }

    int bell(UUID employeeId, String like) {
        return jdbc.queryForObject("SELECT count(*) FROM notifications WHERE employee_id = ? AND text LIKE ?", Integer.class, employeeId, like);
    }

    @Test
    void dueTomorrowIsSentOnceFromNineToBothAssigneesAndReArmedByANewDate() throws Exception {
        Person hr = person(hrRole, "hr"), doer = person(employeeRole, "doer"), accountable = person(employeeRole, "accountable");
        String projectId = project(hr.cookie(), "JOB", "PRIVATE");
        String id = task(hr.cookie(), "\"projectId\":\"" + projectId + "\",\"title\":\"File returns\",\"assigneeId\":\"" + doer.id() + "\",\"indirectAssigneeId\":\""
                + accountable.id() + "\",\"dueDate\":\"2031-03-11\"").get("id").asText();
        assertThat(run("2031-03-10T03:00:00Z").get("dueSoon")).isEqualTo(0); // 08:30 in India: too early
        assertThat(run("2031-03-10T04:00:00Z").get("dueSoon")).isEqualTo(1);
        assertThat(run("2031-03-10T05:00:00Z").get("dueSoon")).isEqualTo(0); // once per due date
        assertThat(bell(doer.id(), "JOB-1 is due tomorrow: File returns")).isEqualTo(1);
        assertThat(bell(accountable.id(), "JOB-1 is due tomorrow: File returns")).isEqualTo(1);
        assertThat(bell(hr.id(), "%due tomorrow%")).isZero();
        // overdue: from 09:00 of the day after the due date (not at midnight)
        assertThat(run("2031-03-12T03:00:00Z").get("overdue")).isEqualTo(0);
        assertThat(run("2031-03-12T03:31:00Z").get("overdue")).isEqualTo(1);
        assertThat(run("2031-03-12T09:00:00Z").get("overdue")).isEqualTo(0);
        assertThat(bell(doer.id(), "JOB-1 is overdue (due 11 Mar 2031): File returns")).isEqualTo(1);
        // a new due date re-arms the reminders
        mockMvc.perform(write(patch("/api/tasks/" + id)).cookie(hr.cookie()).content("{\"version\":0,\"dueDate\":\"2031-03-14\"}")).andExpect(status().isOk());
        assertThat(run("2031-03-13T04:00:00Z").get("dueSoon")).isEqualTo(1);
        assertThat(bell(doer.id(), "JOB-1 is due tomorrow%")).isEqualTo(2);
        // done tasks get nothing
        mockMvc.perform(write(patch("/api/tasks/" + id)).cookie(hr.cookie()).content("{\"version\":1,\"statusCategory\":\"DONE\"}")).andExpect(status().isOk());
        assertThat(run("2031-03-15T04:00:00Z").get("overdue")).isEqualTo(0);
        // bell only
        assertThat(jdbc.queryForObject("SELECT count(*) FROM email_outbox WHERE \"to\" IN (?, ?) AND subject NOT LIKE '%sign-in code%'", Integer.class,
                doer.email(), accountable.email())).isZero();
    }

    @Test
    void timedTasksGetDueTodayAtNineAndOverdueRightAfterTheirTime() throws Exception {
        Person hr = person(hrRole, "hr"), doer = person(employeeRole, "doer");
        task(hr.cookie(), "\"title\":\"Call the client\",\"assigneeId\":\"" + doer.id() + "\",\"dueDate\":\"2031-03-10\",\"dueTime\":\"17:00\"");
        task(hr.cookie(), "\"title\":\"Date only today\",\"assigneeId\":\"" + doer.id() + "\",\"dueDate\":\"2031-03-10\"");
        Map<String, Object> morning = run("2031-03-10T04:00:00Z"); // 09:30 India
        assertThat(morning.get("dueToday")).isEqualTo(1); // timed tasks only
        assertThat(bell(doer.id(), "Personal task is due today at 17:00: Call the client")).isEqualTo(1);
        assertThat(run("2031-03-10T11:29:00Z").get("overdue")).isEqualTo(0); // 16:59
        assertThat(run("2031-03-10T11:31:00Z").get("overdue")).isEqualTo(1); // 17:01: no waiting for 09:00
        assertThat(bell(doer.id(), "Personal task is overdue (due 10 Mar 2031 17:00): Call the client")).isEqualTo(1);
        assertThat(bell(doer.id(), "Personal task is %: Date only today")).isZero();
    }

    @Test
    void theTaskTimeZoneIsTheDirectAssignees() throws Exception {
        Person hr = person(hrRole, "hr"), newYorker = person(employeeRole, "newyork"), leaver = person(employeeRole, "leaver");
        jdbc.update("UPDATE employees SET time_zone = 'America/New_York' WHERE id = ?", newYorker.id());
        task(hr.cookie(), "\"title\":\"Report\",\"assigneeId\":\"" + newYorker.id() + "\",\"indirectAssigneeId\":\"" + hr.id() + "\",\"dueDate\":\"2031-03-11\"");
        // 09:30 in India but 00:00 in New York: not yet; 09:30 in New York: sent to both
        assertThat(run("2031-03-10T04:00:00Z").get("dueSoon")).isEqualTo(0);
        assertThat(run("2031-03-10T13:30:00Z").get("dueSoon")).isEqualTo(1);
        assertThat(bell(hr.id(), "Personal task is due tomorrow: Report")).isEqualTo(1);
        // people who have left are not told
        task(hr.cookie(), "\"title\":\"Old work\",\"assigneeId\":\"" + leaver.id() + "\",\"dueDate\":\"2031-03-11\"");
        jdbc.update("UPDATE employees SET status = 'EXITED' WHERE id = ?", leaver.id());
        run("2031-03-10T04:00:00Z");
        assertThat(bell(leaver.id(), "Personal task is %: Old work")).isZero();
    }

    @Test
    void theExtraReminderIsSentOnceBeforeTheDueMomentNotForDoneTasksAndAgainAfterTheDueDateMoves() throws Exception {
        Person hr = person(hrRole, "hr"), doer = person(employeeRole, "doer"), accountable = person(employeeRole, "accountable");
        String id = task(hr.cookie(), "\"title\":\"Send the offer\",\"assigneeId\":\"" + doer.id() + "\",\"indirectAssigneeId\":\"" + accountable.id()
                + "\",\"dueDate\":\"2031-03-10\",\"dueTime\":\"17:00\",\"reminderBeforeMinutes\":60").get("id").asText();
        task(hr.cookie(), "\"title\":\"No reminder\",\"assigneeId\":\"" + doer.id() + "\",\"dueDate\":\"2031-03-10\",\"dueTime\":\"17:00\"");
        assertThat(run("2031-03-10T10:00:00Z").get("reminders")).isEqualTo(0); // 15:30 India: 30 minutes too early
        assertThat(run("2031-03-10T10:30:00Z").get("reminders")).isEqualTo(1); // 17:00 - 60 minutes = 16:00 India
        assertThat(run("2031-03-10T10:40:00Z").get("reminders")).isEqualTo(0); // once per due moment
        assertThat(bell(doer.id(), "Personal task is due in 1 hour (due 10 Mar 2031 17:00): Send the offer")).isEqualTo(1);
        assertThat(bell(accountable.id(), "Personal task is due in 1 hour (due 10 Mar 2031 17:00): Send the offer")).isEqualTo(1);
        assertThat(bell(doer.id(), "%due in%: No reminder")).isZero();
        // the due time moves: sent again for the new moment
        mockMvc.perform(write(patch("/api/tasks/" + id)).cookie(hr.cookie()).content("{\"version\":0,\"dueTime\":\"20:00\"}")).andExpect(status().isOk());
        assertThat(run("2031-03-10T13:00:00Z").get("reminders")).isEqualTo(0); // 18:30 India: before 19:00
        assertThat(run("2031-03-10T13:31:00Z").get("reminders")).isEqualTo(1); // 19:01 India
        assertThat(bell(doer.id(), "%due in 1 hour (due 10 Mar 2031 20:00): Send the offer")).isEqualTo(1);
        // a new offset sends again too
        mockMvc.perform(write(patch("/api/tasks/" + id)).cookie(hr.cookie()).content("{\"version\":1,\"reminderBeforeMinutes\":1440}")).andExpect(status().isOk());
        assertThat(run("2031-03-10T13:40:00Z").get("reminders")).isEqualTo(1);
        assertThat(bell(doer.id(), "%due in 1 day (due 10 Mar 2031 20:00): Send the offer")).isEqualTo(1);
        // never after the due moment
        mockMvc.perform(write(patch("/api/tasks/" + id)).cookie(hr.cookie()).content("{\"version\":2,\"reminderBeforeMinutes\":15}")).andExpect(status().isOk());
        assertThat(run("2031-03-10T14:45:00Z").get("reminders")).isEqualTo(0); // 20:15 India: already due
        // done tasks get nothing
        mockMvc.perform(write(patch("/api/tasks/" + id)).cookie(hr.cookie()).content("{\"version\":3,\"dueDate\":\"2031-03-20\",\"reminderBeforeMinutes\":240}")).andExpect(status().isOk());
        mockMvc.perform(write(patch("/api/tasks/" + id)).cookie(hr.cookie()).content("{\"version\":4,\"statusCategory\":\"DONE\"}")).andExpect(status().isOk());
        assertThat(run("2031-03-20T15:00:00Z").get("reminders")).isEqualTo(0);
    }

    @Test
    void aDateOnlyTaskIsRemindedBeforeTheEndOfItsDayInTheTaskTimeZone() throws Exception {
        Person hr = person(hrRole, "hr"), newYorker = person(employeeRole, "newyork");
        jdbc.update("UPDATE employees SET time_zone = 'America/New_York' WHERE id = ?", newYorker.id());
        task(hr.cookie(), "\"title\":\"Sign off\",\"assigneeId\":\"" + newYorker.id() + "\",\"dueDate\":\"2031-03-10\",\"reminderBeforeMinutes\":240");
        // due at the end of 10 March New York (00:00 on 11 March = 04:00 UTC); 4 hours before = 00:00 UTC
        assertThat(run("2031-03-10T23:00:00Z").get("reminders")).isEqualTo(0); // 19:00 New York
        assertThat(run("2031-03-11T00:05:00Z").get("reminders")).isEqualTo(1);
        assertThat(bell(newYorker.id(), "Personal task is due in 4 hours (due 10 Mar 2031): Sign off")).isEqualTo(1);
    }
}
