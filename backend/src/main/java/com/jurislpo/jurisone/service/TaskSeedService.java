package com.jurislpo.jurisone.service;

import com.jurislpo.jurisone.engine.TaskAccess;
import com.jurislpo.jurisone.engine.TaskPositions;
import com.jurislpo.jurisone.engine.WorkflowRules;
import com.jurislpo.jurisone.repository.ProjectRepository;
import com.jurislpo.jurisone.repository.TaskCommentRepository;
import com.jurislpo.jurisone.repository.TaskLabelRepository;
import com.jurislpo.jurisone.repository.TaskRepository;
import com.jurislpo.jurisone.repository.TaskRepository.NewTask;
import com.jurislpo.jurisone.repository.WorkflowStatusRepository;
import com.jurislpo.jurisone.repository.WorkflowStatusRepository.StatusRow;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Demo projects and tasks for the demo business (plan §16), dated relative to the seed day so there is always something overdue,
 * due today and due later. Runs inside the demo business; skipped when the projects are already there.
 */
@Service
public class TaskSeedService {

    private final ProjectRepository projects;
    private final WorkflowStatusRepository statuses;
    private final TaskRepository tasks;
    private final TaskLabelRepository labels;
    private final TaskCommentRepository comments;

    public TaskSeedService(ProjectRepository projects, WorkflowStatusRepository statuses, TaskRepository tasks, TaskLabelRepository labels, TaskCommentRepository comments) {
        this.projects = projects;
        this.statuses = statuses;
        this.tasks = tasks;
        this.labels = labels;
        this.comments = comments;
    }

    /** One demo task: step name, people by employee code (null = none), due in days from today (null = no date), optional due time. */
    record Seed(String title, String step, String assignee, String indirectAssignee, Integer dueInDays, String dueTime, String priority) {}

    private Map<String, UUID> codeToId;
    private LocalDate today;

    public void seed(Map<String, UUID> codeToId) {
        if (projects.keyTaken("PEO", null)) return;
        this.codeToId = codeToId;
        this.today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        statuses.insertDefaultSetIfMissing(WorkflowRules.DEFAULT_STATUSES);

        // People operations: company-wide, Riya owns it, Pooja works in it
        UUID people = project("PEO", "People operations", TaskAccess.BUSINESS, "purple", "JC-012", List.of("JC-015"));
        UUID onboarding = task(people, "JC-012", new Seed("Onboarding paperwork for the November joiners", "In progress", "JC-015", "JC-012", -3, null, "HIGH"));
        for (String item : List.of("Offer letters signed", "Bank details collected", "Laptop requests raised")) tasks.insertChecklistItem(UUID.randomUUID(), onboarding, item);
        comment(onboarding, "JC-015", "Two joiners still have to send their bank details. @{" + id("JC-012") + "} can you nudge them?");
        comment(onboarding, "JC-012", "Done, they will send them today.");
        task(people, "JC-012", new Seed("Refresh the leave policy", "To do", "JC-015", "JC-012", 5, null, "MEDIUM"));
        task(people, "JC-012", new Seed("Holiday calendar 2027", "To do", "JC-012", null, 20, null, "MEDIUM"));
        task(people, "JC-012", new Seed("Probation review reminders", "To do", "JC-015", null, -1, null, "HIGH"));
        task(people, "JC-012", new Seed("Training calendar for the next quarter", "In progress", "JC-015", "JC-004", 2, null, "MEDIUM"));
        task(people, "JC-012", new Seed("Update the employee handbook", "In review", "JC-012", null, 7, null, "LOW"));
        task(people, "JC-012", new Seed("Choose a background verification vendor", "Done", "JC-015", "JC-012", -10, null, "MEDIUM"));
        task(people, "JC-012", new Seed("Attendance policy FAQ", "To do", null, null, null, null, "NONE"));

        // Website relaunch: private, Nazneen and her reports
        UUID web = project("WEB", "Website relaunch", TaskAccess.PRIVATE, "blue", "JC-035", List.of("JC-049", "JC-058", "JC-072", "JC-074"));
        UUID frontend = label(web, "frontend", "blue"), content = label(web, "content", "green");
        UUID design = task(web, "JC-035", new Seed("New home page design", "In progress", "JC-074", "JC-035", 4, null, "HIGH"));
        tasks.setLabels(design, List.of(frontend));
        UUID hero = task(web, "JC-035", new Seed("Hero section copy", "To do", "JC-058", "JC-035", 3, null, "MEDIUM"), design);
        tasks.setLabels(hero, List.of(content));
        task(web, "JC-035", new Seed("Mobile navigation", "To do", "JC-074", null, 6, null, "MEDIUM"), design);
        UUID launch = task(web, "JC-035", new Seed("Launch checklist review", "In review", "JC-072", "JC-035", 0, "17:00", "URGENT"));
        comment(launch, "JC-072", "Checklist is ready, waiting for the content sign-off.");
        comment(launch, "JC-035", "Thanks. @{" + id("JC-058") + "} please confirm the copy by 4 pm.");
        comment(launch, "JC-058", "Confirmed, all pages are final.");
        task(web, "JC-035", new Seed("Fix the contact form", "In progress", "JC-049", "JC-035", -1, "11:00", "HIGH"));
        UUID blog = task(web, "JC-035", new Seed("Migrate the blog posts", "To do", "JC-058", null, 12, null, "LOW"));
        tasks.setLabels(blog, List.of(content));
        task(web, "JC-035", new Seed("Set up analytics", "To do", "JC-049", null, null, null, "NONE"));
        task(web, "JC-035", new Seed("Domain and SSL renewal", "Done", "JC-074", "JC-035", -5, null, "MEDIUM"));
        task(web, "JC-035", new Seed("Old site redirects", "Cancelled", "JC-049", null, null, null, "LOW"));
        UUID speed = task(web, "JC-035", new Seed("Page speed audit", "In review", "JC-074", null, 1, null, "MEDIUM"));
        tasks.setLabels(speed, List.of(frontend));

        // Payroll October: private to Finance (HR must not see it; the Owner can)
        UUID payroll = project("PAY", "Payroll October", TaskAccess.PRIVATE, "green", "JC-004", List.of("JC-018"));
        task(payroll, "JC-004", new Seed("Collect attendance for October", "Done", "JC-018", "JC-004", -8, null, "HIGH"));
        task(payroll, "JC-004", new Seed("Check new joiners' salary structures", "In progress", "JC-018", "JC-004", 1, null, "HIGH"));
        task(payroll, "JC-004", new Seed("Bonus approvals", "To do", "JC-004", null, 4, null, "MEDIUM"));
        task(payroll, "JC-004", new Seed("Publish payslips", "To do", "JC-004", null, 9, null, "URGENT"));

        // two personal tasks of Siddharth
        task(null, "JC-072", new Seed("Renew my parking pass", "To do", "JC-072", null, 2, null, "LOW"));
        task(null, "JC-072", new Seed("Read the new review guidelines", "In progress", "JC-072", null, null, null, "NONE"));
    }

    private UUID id(String code) {
        return codeToId.get(code);
    }

    private UUID project(String key, String name, String visibility, String color, String ownerCode, List<String> memberCodes) {
        UUID projectId = UUID.randomUUID();
        projects.insert(projectId, key, name, null, visibility, color, null, id(ownerCode));
        projects.addMember(projectId, id(ownerCode), TaskAccess.OWNER, id(ownerCode));
        for (String code : memberCodes) projects.addMember(projectId, id(code), TaskAccess.MEMBER, id(ownerCode));
        statuses.copyDefaultSet(projectId);
        return projectId;
    }

    private UUID label(UUID projectId, String name, String color) {
        UUID labelId = UUID.randomUUID();
        labels.insert(labelId, projectId, name, color);
        return labelId;
    }

    private UUID task(UUID projectId, String reporterCode, Seed seed) {
        return task(projectId, reporterCode, seed, null);
    }

    private UUID task(UUID projectId, String reporterCode, Seed seed, UUID parentTaskId) {
        StatusRow status = statuses.findSet(projectId).stream().filter(s -> s.name().equals(seed.step())).findFirst().orElseThrow();
        UUID taskId = UUID.randomUUID();
        Integer number = projectId == null ? null : tasks.takeNextNumber(projectId);
        UUID assigneeId = seed.assignee() == null ? null : id(seed.assignee()), indirectAssigneeId = seed.indirectAssignee() == null ? null : id(seed.indirectAssignee());
        tasks.insert(new NewTask(taskId, projectId, number, parentTaskId, seed.title(), null, "TASK", status.id(), seed.priority(), assigneeId, indirectAssigneeId,
                id(reporterCode), null, seed.dueInDays() == null ? null : today.plusDays(seed.dueInDays()), seed.dueTime() == null ? null : LocalTime.parse(seed.dueTime()),
                null, null, TaskPositions.append(tasks.findMaxPosition(projectId, status.id())), WorkflowRules.DONE.equals(status.category())));
        Set<UUID> followers = new LinkedHashSet<>();
        followers.add(id(reporterCode));
        if (assigneeId != null) followers.add(assigneeId);
        if (indirectAssigneeId != null) followers.add(indirectAssigneeId);
        for (UUID followerId : followers) tasks.addWatcher(taskId, followerId);
        if (projectId != null && assigneeId != null) projects.addMember(projectId, assigneeId, TaskAccess.MEMBER, id(reporterCode));
        return taskId;
    }

    private void comment(UUID taskId, String authorCode, String body) {
        Set<UUID> mentioned = com.jurislpo.jurisone.engine.Mentions.find(body);
        comments.insert(UUID.randomUUID(), taskId, id(authorCode), body, mentioned);
        tasks.addWatcher(taskId, id(authorCode));
        for (UUID mentionedId : mentioned) tasks.addWatcher(taskId, mentionedId);
    }
}
