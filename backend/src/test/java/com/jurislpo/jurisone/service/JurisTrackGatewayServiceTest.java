package com.jurislpo.jurisone.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jurislpo.jurisone.config.JurisTrackProperties;
import com.jurislpo.jurisone.exception.ApiException;
import com.jurislpo.jurisone.security.JurisTrackTokenIssuer;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** The parts of the gateway that need no database or JurisTrack: the target path rules and the switched-off answer. */
class JurisTrackGatewayServiceTest {

    @Test
    void thePathIsWhatFollowsTheGatewayPrefix() {
        assertThat(JurisTrackGatewayService.targetPath("/api/v1/juris-track/workdays/mine")).isEqualTo("workdays/mine");
        // JurisTrack's paths are words and ids: percent-encoding is refused outright (it could hide "integration", ".." or "/")
        assertThatThrownBy(() -> JurisTrackGatewayService.targetPath("/api/v1/juris-track/employees/a%20b/shift")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> JurisTrackGatewayService.targetPath("/api/v1/juris-track/%69ntegration/employees")).isInstanceOf(ApiException.class);
    }

    @Test
    void climbingOutOrEmptyPathsAreBadRequestsAndServiceEndpointsAreNotFound() {
        for (String uri : new String[] {"/api/v1/juris-track/", "/api/v1/juris-track/../x", "/api/v1/juris-track/a/%2E%2e/b", "/api/v1/juris-track/a%2fb", "/api/v1/juris-track/a//b", "/api/v1/juris-track/a%5Cb"}) {
            assertThatThrownBy(() -> JurisTrackGatewayService.targetPath(uri)).as(uri).isInstanceOf(ApiException.class).extracting(e -> ((ApiException) e).status()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
        assertThatThrownBy(() -> JurisTrackGatewayService.targetPath("/api/v1/juris-track/integration/employees")).extracting(e -> ((ApiException) e).status()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void aSwitchedOffGatewayAnswers503() {
        JurisTrackProperties properties = new JurisTrackProperties(false, "", "", null, 0, "");
        JurisTrackGatewayService gateway = new JurisTrackGatewayService(properties, new JurisTrackTokenIssuer(properties), null, null, null);
        assertThatThrownBy(() -> gateway.forward(null, new MockHttpServletRequest("GET", "/api/v1/juris-track/me"), new MockHttpServletResponse()))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(e.code()).isEqualTo("JURISTRACK_DISABLED");
                });
    }
}
