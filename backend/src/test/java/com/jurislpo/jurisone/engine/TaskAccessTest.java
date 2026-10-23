package com.jurislpo.jurisone.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.jurislpo.jurisone.engine.TaskAccess.Facts;
import com.jurislpo.jurisone.engine.TaskAccess.TaskPermissions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The who-sees-what matrix of the task management plan (§4.3, §4.4). */
@DisplayName("task access")
class TaskAccessTest {

    static Facts project(String visibility, String role) {
        return new Facts(false, true, visibility, role, false, false, false, false, false);
    }

    static Facts personal() {
        return new Facts(false, false, null, null, false, false, false, false, false);
    }

    static Facts with(Facts facts, String part) {
        return new Facts(part.equals("admin") || facts.isTaskAdmin(), facts.hasProject(), facts.visibility(), facts.projectRole(),
                part.equals("direct") || facts.isDirectAssignee(), part.equals("indirect") || facts.isIndirectAssignee(), part.equals("reporter") || facts.isReporter(),
                part.equals("watcher") || facts.isWatcher(), part.equals("manager") || facts.isManagerOfAssignee());
    }

    static void readAndCommentOnly(TaskPermissions permissions) {
        assertThat(permissions.canView()).isTrue();
        assertThat(permissions.canComment()).isTrue();
        assertThat(permissions.canEdit()).isFalse();
        assertThat(permissions.canChangeStatus()).isFalse();
        assertThat(permissions.canChangePriority()).isFalse();
        assertThat(permissions.canChangeDirectAssignee()).isFalse();
        assertThat(permissions.canDelete()).isFalse();
        assertThat(permissions.canManageProject()).isFalse();
    }

    static void nothing(TaskPermissions permissions) {
        assertThat(permissions).isEqualTo(new TaskPermissions(false, false, false, false, false, false, false, false, false));
    }

    @Test
    @DisplayName("the Owner acts as project owner everywhere, private projects included")
    void owner() {
        TaskPermissions permissions = TaskAccess.of(with(project("PRIVATE", null), "admin"));
        assertThat(permissions).isEqualTo(new TaskPermissions(true, true, true, true, true, true, true, true, true));
        assertThat(TaskAccess.isPrivateAccess(with(project("PRIVATE", null), "admin"))).isTrue();
        assertThat(TaskAccess.isPrivateAccess(with(project("PRIVATE", "MEMBER"), "admin"))).isFalse();
        assertThat(TaskAccess.isPrivateAccess(with(project("BUSINESS", null), "admin"))).isFalse();
        assertThat(TaskAccess.isPrivateAccess(project("PRIVATE", null))).isFalse();
    }

    @Test
    @DisplayName("project roles: owner manages, member edits and deletes what they reported, viewer reads and comments")
    void projectRoles() {
        assertThat(TaskAccess.of(project("PRIVATE", "OWNER")).canManageProject()).isTrue();
        TaskPermissions member = TaskAccess.of(project("PRIVATE", "MEMBER"));
        assertThat(member.canEdit()).isTrue();
        assertThat(member.canChangeIndirectAssignee()).isTrue();
        assertThat(member.canDelete()).isFalse();
        assertThat(member.canManageProject()).isFalse();
        assertThat(TaskAccess.of(with(project("PRIVATE", "MEMBER"), "reporter")).canDelete()).isTrue();
        readAndCommentOnly(TaskAccess.of(project("PRIVATE", "VIEWER")));
    }

    @Test
    @DisplayName("a company-wide project reads like a viewer for non-members; a private one is invisible")
    void nonMembers() {
        readAndCommentOnly(TaskAccess.of(project("BUSINESS", null)));
        nothing(TaskAccess.of(project("PRIVATE", null)));
    }

    @Test
    @DisplayName("the direct assignee can work on the task (status) even without membership")
    void directAssignee() {
        TaskPermissions permissions = TaskAccess.of(with(project("PRIVATE", null), "direct"));
        assertThat(permissions.canView()).isTrue();
        assertThat(permissions.canChangeStatus()).isTrue();
        assertThat(permissions.canEdit()).isFalse();
        assertThat(permissions.canDelete()).isFalse();
    }

    @Test
    @DisplayName("the indirect assignee comments, changes status and priority and reassigns, but edits nothing else and cannot delete")
    void indirectAssignee() {
        TaskPermissions permissions = TaskAccess.of(with(project("PRIVATE", null), "indirect"));
        assertThat(permissions.canComment()).isTrue();
        assertThat(permissions.canChangeStatus()).isTrue();
        assertThat(permissions.canChangePriority()).isTrue();
        assertThat(permissions.canChangeDirectAssignee()).isTrue();
        assertThat(permissions.canChangeIndirectAssignee()).isFalse();
        assertThat(permissions.canEdit()).isFalse();
        assertThat(permissions.canDelete()).isFalse();
        // also a project owner: may delete
        assertThat(TaskAccess.of(with(project("PRIVATE", "OWNER"), "indirect")).canDelete()).isTrue();
        // on a personal task the same rights
        TaskPermissions personal = TaskAccess.of(with(personal(), "indirect"));
        assertThat(personal.canChangeDirectAssignee()).isTrue();
        assertThat(personal.canEdit()).isFalse();
    }

    @Test
    @DisplayName("reporter, follower and the assignee's manager read and comment")
    void readers() {
        readAndCommentOnly(TaskAccess.of(with(project("PRIVATE", null), "reporter")));
        readAndCommentOnly(TaskAccess.of(with(project("PRIVATE", null), "watcher")));
        readAndCommentOnly(TaskAccess.of(with(project("PRIVATE", null), "manager")));
        readAndCommentOnly(TaskAccess.of(with(personal(), "manager")));
        readAndCommentOnly(TaskAccess.of(with(personal(), "watcher")));
    }

    @Test
    @DisplayName("personal tasks: the reporter does everything, the assignee edits, the Owner reads, others see nothing")
    void personalTasks() {
        TaskPermissions reporter = TaskAccess.of(with(personal(), "reporter"));
        assertThat(reporter.canEdit()).isTrue();
        assertThat(reporter.canDelete()).isTrue();
        assertThat(reporter.canChangeDirectAssignee()).isTrue();
        assertThat(reporter.canChangeIndirectAssignee()).isTrue();
        TaskPermissions assignee = TaskAccess.of(with(personal(), "direct"));
        assertThat(assignee.canEdit()).isTrue();
        assertThat(assignee.canDelete()).isFalse();
        assertThat(assignee.canChangeDirectAssignee()).isFalse();
        readAndCommentOnly(TaskAccess.of(with(personal(), "admin")));
        assertThat(TaskAccess.isPrivateAccess(with(personal(), "admin"))).isTrue();
        nothing(TaskAccess.of(personal()));
    }
}
