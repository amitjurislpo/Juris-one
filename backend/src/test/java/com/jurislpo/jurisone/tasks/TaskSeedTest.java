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
        Map<String, UUID> roles = Map.of("JC002", ownerRole, "JC004", financeRole, "JC012", hrRole, "JC015", employeeRole, "JC018", employeeRole, "JC035", employeeRole,
                "JC049", employeeRole, "JC058", employeeRole, "JC072", employeeRole, "JC074", employeeRole);
        for (String code : List.of("JC002", "JC004", "JC012", "JC015", "JC018", "JC035", "JC049", "JC058", "JC072", "JC074")) {
            codeToId.put(code, data.employee(code, code.toLowerCase() + "." + suffix + "@example.com", roles.get(code)));
        }
        TenantContext.run(data.business(), () -> taskSeed.seed(codeToId));
        TenantContext.run(data.business(), () -> taskSeed.seed(codeToId)); // skipped the second time
        assertThat(jdbc.queryForObject("SELECT count(*) FROM projects WHERE business_id = ?", Integer.class, data.business())).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tasks WHERE business_id = ?", Integer.class, data.business())).isEqualTo(24);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM task_comments WHERE business_id = ?", Integer.class, data.business())).isEqualTo(5);

        Cookie owner = signIn("jc002." + suffix + "@example.com"), hr = signIn("jc012." + suffix + "@example.com");
        assertThat(getData(owner, "/api/v1/projects").get("total").asInt()).isEqualTo(3);
        assertThat(getData(hr, "/api/v1/projects").get("rows").findValuesAsText("key")).containsExactly("PEO");
        mockMvc.perform(get("/api/v1/tasks/PAY-1").cookie(hr)).andExpect(status().isNotFound());
        assertThat(getData(owner, "/api/v1/tasks/PAY-1").get("title").asText()).isEqualTo("Collect attendance for October");
        Cookie pooja = signIn("jc015." + suffix + "@example.com");
        assertThat(getData(pooja, "/api/v1/tasks/mine").get("counts").get("overdue").asInt()).isEqualTo(2);
        assertThat(getData(pooja, "/api/v1/tasks/PEO-1").get("checklist").size()).isEqualTo(3);
        Cookie nazneen = signIn("jc035." + suffix + "@example.com");
        assertThat(getData(nazneen, "/api/v1/projects/" + jdbc.queryForObject("SELECT id::text FROM projects WHERE key = 'WEB' AND business_id = ?", String.class, data.business())
                + "/board").get("columns").size()).isEqualTo(5);
    }
}
