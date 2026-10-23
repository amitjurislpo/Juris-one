package com.jurislpo.jurisone.tasks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jurislpo.jurisone.service.TaskSeedService;
import com.jurislpo.jurisone.tenancy.TenantContext;
import jakarta.servlet.http.Cookie;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** The demo projects and tasks (plan §16) load into a business with the demo people, once. */
class TaskSeedTest extends TaskApiTestBase {

    @Autowired TaskSeedService taskSeed;

    @Test
    void theDemoTasksLoadOnceAndFollowTheVisibilityRules() throws Exception {
        Map<String, UUID> codeToId = new LinkedHashMap<>();
        Map<String, UUID> roles = Map.of("JC-002", ownerRole, "JC-004", financeRole, "JC-012", hrRole, "JC-015", employeeRole, "JC-018", employeeRole, "JC-035", employeeRole,
                "JC-049", employeeRole, "JC-058", employeeRole, "JC-072", employeeRole, "JC-074", employeeRole);
        for (String code : List.of("JC-002", "JC-004", "JC-012", "JC-015", "JC-018", "JC-035", "JC-049", "JC-058", "JC-072", "JC-074")) {
            codeToId.put(code, data.employee(code, code.toLowerCase() + "." + suffix + "@example.com", roles.get(code)));
        }
        TenantContext.run(data.business(), () -> taskSeed.seed(codeToId));
        TenantContext.run(data.business(), () -> taskSeed.seed(codeToId)); // skipped the second time
        assertThat(jdbc.queryForObject("SELECT count(*) FROM projects WHERE business_id = ?", Integer.class, data.business())).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tasks WHERE business_id = ?", Integer.class, data.business())).isEqualTo(24);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM task_comments WHERE business_id = ?", Integer.class, data.business())).isEqualTo(5);

        Cookie owner = signIn("jc-002." + suffix + "@example.com"), hr = signIn("jc-012." + suffix + "@example.com");
        assertThat(getData(owner, "/api/projects").get("total").asInt()).isEqualTo(3);
        assertThat(getData(hr, "/api/projects").get("rows").findValuesAsText("key")).containsExactly("PEO");
        mockMvc.perform(get("/api/tasks/PAY-1").cookie(hr)).andExpect(status().isNotFound());
        assertThat(getData(owner, "/api/tasks/PAY-1").get("title").asText()).isEqualTo("Collect attendance for October");
        Cookie pooja = signIn("jc-015." + suffix + "@example.com");
        assertThat(getData(pooja, "/api/tasks/mine").get("counts").get("overdue").asInt()).isEqualTo(2);
        assertThat(getData(pooja, "/api/tasks/PEO-1").get("checklist").size()).isEqualTo(3);
        Cookie nazneen = signIn("jc-035." + suffix + "@example.com");
        assertThat(getData(nazneen, "/api/projects/" + jdbc.queryForObject("SELECT id::text FROM projects WHERE key = 'WEB' AND business_id = ?", String.class, data.business())
                + "/board").get("columns").size()).isEqualTo(5);
    }
}
