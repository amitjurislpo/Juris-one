package com.jurislpo.jurisone.engine;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** A workflow (the steps of a project, or the business default set): plan §8.4. */
public final class WorkflowRules {
    private WorkflowRules() {}

    public static final String TODO = "TODO", IN_PROGRESS = "IN_PROGRESS", DONE = "DONE";
    public static final List<String> CATEGORIES = List.of(TODO, IN_PROGRESS, DONE);
    public static final List<String> COLORS = List.of("purple", "blue", "green", "amber", "red", "grey");
    public static final int MAX_STATUSES = 12;

    /** One step of a workflow. */
    public record Status(String name, String category, String color, boolean isInitial) {}

    /** The built-in default steps (decided by the owner): To do, In progress, In review, Done, Cancelled. */
    public static final List<Status> DEFAULT_STATUSES = List.of(new Status("To do", TODO, "grey", true), new Status("In progress", IN_PROGRESS, "blue", false),
            new Status("In review", IN_PROGRESS, "purple", false), new Status("Done", DONE, "green", false), new Status("Cancelled", DONE, "red", false));

    /** The problem with this set of steps, as a sentence for the user, or null when it is valid. */
    public static String problem(List<Status> statuses) {
        if (statuses.size() > MAX_STATUSES) return "A workflow can have at most " + MAX_STATUSES + " steps.";
        if (statuses.stream().noneMatch(s -> TODO.equals(s.category()))) return "A workflow needs at least one to-do step.";
        if (statuses.stream().noneMatch(s -> DONE.equals(s.category()))) return "A workflow needs at least one done step.";
        long initial = statuses.stream().filter(Status::isInitial).count();
        if (initial != 1) return "Exactly one step must be the one new tasks start in.";
        if (statuses.stream().anyMatch(s -> s.isInitial() && !TODO.equals(s.category()))) return "New tasks must start in a to-do step.";
        Set<String> names = new HashSet<>();
        for (Status status : statuses) {
            if (!names.add(status.name().toLowerCase(Locale.ROOT))) return "Each step needs its own name.";
        }
        return null;
    }
}
