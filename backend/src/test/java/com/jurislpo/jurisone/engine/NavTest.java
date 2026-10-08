package com.jurislpo.jurisone.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Port of tests/unit/nav.test.ts. */
@DisplayName("navigation access")
class NavTest {

    static Set<String> set(String... p) { return Set.of(p); }

    static List<String> visible(Set<String> perms, boolean reports) {
        return Nav.visible(perms, reports).stream().map(Nav.Item::id).toList();
    }

    static final Set<String> ADMIN;
    static {
        Set<String> s = new HashSet<>(Permissions.KEYS);
        s.remove("self");
        ADMIN = Set.copyOf(s);
    }
    static final Set<String> HR_MANAGER = set("self", "dir", "att.all", "appr.leave", "appr.reg", "people.view", "people.add", "access.toggle", "dept.manage", "holidays.manage");
    static final Set<String> FIN_EXEC = set("self", "dir", "appr.reg", "pay.view", "tax.view", "proofs.verify");
    static final Set<String> EMPLOYEE = set("self", "dir");

    @Test
    @DisplayName("lands Admin on Overview and everyone with self on Home")
    void landing() {
        assertThat(Nav.landingFor(ADMIN)).isEqualTo("/overview");
        assertThat(Nav.landingFor(EMPLOYEE)).isEqualTo("/home");
        assertThat(Nav.landingFor(HR_MANAGER)).isEqualTo("/home");
    }

    @Test
    @DisplayName("gives Admin no self-service pages")
    void adminNoSelf() {
        List<String> v = visible(ADMIN, false);
        assertThat(v).doesNotContain("home", "attendance", "leave", "payslips", "declare");
        assertThat(v).contains("roles");
    }

    @Test
    @DisplayName("never shows finance pages to HR")
    void hrNoFinance() {
        assertThat(visible(HR_MANAGER, false)).doesNotContain("payroll", "structures", "tax", "proofs");
        for (String p : Permissions.SALARY) assertThat(HR_MANAGER).doesNotContain(p);
    }

    @Test
    @DisplayName("shows My team only with active direct reports")
    void team() {
        assertThat(visible(EMPLOYEE, false)).doesNotContain("team");
        assertThat(visible(EMPLOYEE, true)).contains("team");
    }

    @Test
    @DisplayName("lets Finance Executive approve regularizations but not leave")
    void finExec() {
        List<String> v = visible(FIN_EXEC, false);
        assertThat(v).contains("approvals", "payroll");
        assertThat(v).doesNotContain("register");
    }

    @Test
    @DisplayName("shows Juris Track only when the business has the module")
    void jurisTrack() {
        assertThat(visible(EMPLOYEE, false)).doesNotContain("juris-track");
        assertThat(Nav.visible(EMPLOYEE, false, set("JURIS_TRACK")).stream().map(Nav.Item::id)).contains("juris-track");
        assertThat(Nav.visible(ADMIN, false, set("JURIS_TRACK")).stream().map(Nav.Item::id)).contains("juris-track");
        assertThat(Nav.landingFor(set("dir"))).isEqualTo("/directory");
    }
}
