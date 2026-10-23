package com.jurislpo.jurisone.engine;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("project and task keys")
class TaskKeysTest {

    @Test
    @DisplayName("a project key is 2 to 6 capital letters or digits, starting with a letter")
    void projectKeys() {
        assertThat(TaskKeys.isProjectKey("HR")).isTrue();
        assertThat(TaskKeys.isProjectKey("PAY2026")).isFalse();
        assertThat(TaskKeys.isProjectKey("PAY202")).isTrue();
        assertThat(TaskKeys.isProjectKey("H")).isFalse();
        assertThat(TaskKeys.isProjectKey("1HR")).isFalse();
        assertThat(TaskKeys.isProjectKey("hr")).isFalse();
        assertThat(TaskKeys.isProjectKey(null)).isFalse();
    }

    @Test
    @DisplayName("task keys are formatted and parsed case-insensitively")
    void taskKeys() {
        assertThat(TaskKeys.formatKey("HR", 12)).isEqualTo("HR-12");
        assertThat(TaskKeys.parseKey("hr-12")).contains(new TaskKeys.ParsedKey("HR", 12));
        assertThat(TaskKeys.parseKey(" PAY-3 ")).contains(new TaskKeys.ParsedKey("PAY", 3));
        assertThat(TaskKeys.parseKey("HR12")).isEmpty();
        assertThat(TaskKeys.parseKey("HR-")).isEmpty();
        assertThat(TaskKeys.parseKey("12-HR")).isEmpty();
        assertThat(TaskKeys.parseKey(null)).isEmpty();
    }
}
