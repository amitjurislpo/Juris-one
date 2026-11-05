package com.jurislpo.jurisone.people;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jurislpo.jurisone.IntegrationTest;
import com.jurislpo.jurisone.SignIn;
import com.jurislpo.jurisone.TestData;
import com.jurislpo.jurisone.repository.AuthRepository;
import com.jurislpo.jurisone.repository.PeopleRepository;
import com.jurislpo.jurisone.service.EncryptExistingColumnsService;
import com.jurislpo.jurisone.tenancy.TenantContext;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The end state of this stage: lookup by blind index with the plaintext fallback OFF (what the owner sets once {@code encrypt-existing} reports no row without an index).
 * A row without an index is then not found (which is why the backfill must be finished first); after the backfill it is found again.
 */
@TestPropertySource(properties = {"jurisone.security.encrypt-email.lookup-by-blind-index=true", "jurisone.security.encrypt-email.plaintext-fallback=false"})
class EncryptedEmailLookupNoFallbackTest extends IntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired AuthRepository authRepository;
    @Autowired PeopleRepository peopleRepository;
    @Autowired EncryptExistingColumnsService backfill;

    @Test
    void onlyRowsWithAnIndexAreFoundAndTheBackfillMakesTheRestFindable() throws Exception {
        String suffix = TestData.letters(8);
        TestData data = new TestData(jdbc).inBusiness("nofb-" + suffix);
        UUID role = data.role("employee", "self", "dir");
        String email = "late." + suffix + "@example.com";
        UUID id = data.employee("LT" + suffix, email, role); // written without index (an old row)
        assertThat(authRepository.findEmployeesByEmail(email)).isEmpty(); // the fallback is off: this is why the backfill comes first
        assertThat(TenantContext.call(data.business(), () -> peopleRepository.findCodeByEmail(email))).isEmpty();
        SignIn.session(mockMvc, email).andExpect(status().isUnauthorized());

        assertThat(backfill.run().get("employees.email_blind_index_still_missing")).isZero();
        assertThat(authRepository.findEmployeesByEmail(email)).extracting(employee -> employee.getId()).containsExactly(id);
        assertThat(authRepository.findEmployeesByEmail("  " + email.toUpperCase() + " ")).hasSize(1); // the index is made from the normalised address
        SignIn.session(mockMvc, email).andExpect(status().isCreated());
        assertThat(TenantContext.call(data.business(), () -> peopleRepository.findCodeByEmail(email))).containsExactly("LT" + suffix);
    }
}
