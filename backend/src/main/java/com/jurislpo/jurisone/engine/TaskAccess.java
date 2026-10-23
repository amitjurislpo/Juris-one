package com.jurislpo.jurisone.engine;

/**
 * Who may do what with one task (task management plan §4.3 / §4.4). Pure: the caller collects the facts.
 * A task the caller cannot view is answered with 404, so its existence does not leak; the Owner ({@code tasks.manage}) is the only
 * one who sees every project and task.
 */
public final class TaskAccess {
    private TaskAccess() {}

    public static final String OWNER = "OWNER", MEMBER = "MEMBER", VIEWER = "VIEWER";
    public static final String PRIVATE = "PRIVATE", BUSINESS = "BUSINESS";

    /**
     * @param isTaskAdmin        holds {@code tasks.manage} (the Owner)
     * @param hasProject         false for a personal task
     * @param visibility         the project's {@code PRIVATE} / {@code BUSINESS} (ignored for a personal task)
     * @param projectRole        the user's role in the project, or null
     * @param isManagerOfAssignee the direct assignee is in the user's reporting scope (depth ≤ 2)
     */
    public record Facts(boolean isTaskAdmin, boolean hasProject, String visibility, String projectRole, boolean isDirectAssignee, boolean isIndirectAssignee,
                        boolean isReporter, boolean isWatcher, boolean isManagerOfAssignee) {}

    /** {@code canEdit} = every field; the narrower flags exist for the indirect assignee. */
    public record TaskPermissions(boolean canView, boolean canComment, boolean canEdit, boolean canChangeStatus, boolean canChangePriority,
                                  boolean canChangeDirectAssignee, boolean canChangeIndirectAssignee, boolean canDelete, boolean canManageProject) {

        static final TaskPermissions NONE = new TaskPermissions(false, false, false, false, false, false, false, false, false);
        static final TaskPermissions READ_AND_COMMENT = new TaskPermissions(true, true, false, false, false, false, false, false, false);

        TaskPermissions or(TaskPermissions other) {
            return new TaskPermissions(canView || other.canView, canComment || other.canComment, canEdit || other.canEdit, canChangeStatus || other.canChangeStatus,
                    canChangePriority || other.canChangePriority, canChangeDirectAssignee || other.canChangeDirectAssignee,
                    canChangeIndirectAssignee || other.canChangeIndirectAssignee, canDelete || other.canDelete, canManageProject || other.canManageProject);
        }
    }

    /** The project role that applies: the Owner acts as project owner everywhere; a non-member of a company-wide project reads like a viewer. */
    public static String effectiveProjectRole(boolean isTaskAdmin, String visibility, String projectRole) {
        if (isTaskAdmin) return OWNER;
        if (projectRole != null) return projectRole;
        return BUSINESS.equals(visibility) ? VIEWER : null;
    }

    public static TaskPermissions of(Facts facts) {
        return facts.hasProject() ? projectTask(facts) : personalTask(facts);
    }

    private static TaskPermissions projectTask(Facts facts) {
        TaskPermissions result = TaskPermissions.NONE;
        String role = effectiveProjectRole(facts.isTaskAdmin(), facts.visibility(), facts.projectRole());
        if (OWNER.equals(role)) result = new TaskPermissions(true, true, true, true, true, true, true, true, true);
        else if (MEMBER.equals(role)) result = new TaskPermissions(true, true, true, true, true, true, true, facts.isReporter(), false);
        else if (VIEWER.equals(role)) result = TaskPermissions.READ_AND_COMMENT;
        if (facts.isDirectAssignee()) result = result.or(new TaskPermissions(true, true, false, true, false, false, false, false, false));
        if (facts.isIndirectAssignee()) result = result.or(new TaskPermissions(true, true, false, true, true, true, false, false, false));
        if (facts.isReporter() || facts.isWatcher() || facts.isManagerOfAssignee()) result = result.or(TaskPermissions.READ_AND_COMMENT);
        return result;
    }

    private static TaskPermissions personalTask(Facts facts) {
        TaskPermissions result = TaskPermissions.NONE;
        if (facts.isReporter()) result = new TaskPermissions(true, true, true, true, true, true, true, true, false);
        if (facts.isDirectAssignee()) result = result.or(new TaskPermissions(true, true, true, true, true, false, false, false, false));
        if (facts.isIndirectAssignee()) result = result.or(new TaskPermissions(true, true, false, true, true, true, false, false, false));
        if (facts.isTaskAdmin() || facts.isWatcher() || facts.isManagerOfAssignee()) result = result.or(TaskPermissions.READ_AND_COMMENT);
        return result;
    }

    /** True when the Owner opens something private they are not part of: such a read is written to the audit log. */
    public static boolean isPrivateAccess(Facts facts) {
        if (!facts.isTaskAdmin()) return false;
        boolean isParticipant = facts.projectRole() != null || facts.isDirectAssignee() || facts.isIndirectAssignee() || facts.isReporter() || facts.isWatcher();
        if (isParticipant) return false;
        return !facts.hasProject() || PRIVATE.equals(facts.visibility());
    }
}
