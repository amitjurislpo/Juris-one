package com.jurislpo.jurisone;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Signs in the way the web app does: ask for the emailed code, then post it (dev and tests use the fixed code 000000). */
public final class SignIn {

    public static final String CODE = "000000";

    private SignIn() {}

    /** Requests a code for {@code email} and signs in with it; the returned actions are those of the sign-in call. */
    public static ResultActions session(MockMvc mvc, String email) throws Exception {
        mvc.perform(post("/api/auth/otp-requests").header("origin", "http://localhost:3005").header("host", "localhost:3005")
                .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"" + email + "\"}")).andExpect(status().isCreated());
        return mvc.perform(post("/api/auth/session").header("origin", "http://localhost:3005").header("host", "localhost:3005")
                .contentType(MediaType.APPLICATION_JSON).content("{\"identifier\":\"" + email + "\",\"otp\":\"" + CODE + "\"}"));
    }
}
