package com.jurislpo.jurisone.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.jurislpo.jurisone.engine.WorkflowRules.Status;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("workflow rules")
class WorkflowRulesTest {

    @Test
    @DisplayName("the built-in steps are To do, In progress, In review, Done, Cancelled and are valid")
    void defaults() {
        assertThat(WorkflowRules.DEFAULT_STATUSES.stream().map(Status::name)).containsExactly("To do", "In progress", "In review", "Done", "Cancelled");
        assertThat(WorkflowRules.problem(WorkflowRules.DEFAULT_STATUSES)).isNull();
    }

    @Test
    @DisplayName("a workflow keeps a to-do and a done step, one starting step in to-do, unique names and at most 12 steps")
    void problems() {
        Status todo = new Status("To do", "TODO", null, true), done = new Status("Done", "DONE", null, false);
        assertThat(WorkflowRules.problem(List.of(done))).contains("to-do");
        assertThat(WorkflowRules.problem(List.of(todo))).contains("done");
        assertThat(WorkflowRules.problem(List.of(new Status("To do", "TODO", null, false), done))).contains("Exactly one");
        assertThat(WorkflowRules.problem(List.of(todo, new Status("Doing", "TODO", null, true), done))).contains("Exactly one");
        assertThat(WorkflowRules.problem(List.of(new Status("To do", "TODO", null, false), new Status("Done", "DONE", null, true)))).contains("to-do step");
        assertThat(WorkflowRules.problem(List.of(todo, done, new Status("DONE", "DONE", null, false)))).contains("own name");
        List<Status> many = new ArrayList<>(List.of(todo, done));
        for (int i = 0; i < 11; i++) many.add(new Status("Step " + i, "IN_PROGRESS", null, false));
        assertThat(WorkflowRules.problem(many)).contains("at most 12");
        assertThat(WorkflowRules.problem(many.subList(0, 12))).isNull();
    }
}
