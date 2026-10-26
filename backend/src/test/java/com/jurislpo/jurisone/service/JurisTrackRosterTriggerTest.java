package com.jurislpo.jurisone.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jurislpo.jurisone.config.JurisTrackRosterProperties;
import com.jurislpo.jurisone.exception.ApiException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** When the roster is pushed: coalesced per business, only for a business with the module, never when the gateway is off, never throwing. */
class JurisTrackRosterTriggerTest {

    JurisTrackRosterService roster;
    JurisTrackRosterTrigger trigger;

    @BeforeEach
    void setUp() {
        roster = mock(JurisTrackRosterService.class);
        when(roster.isGatewayOn()).thenReturn(true);
        when(roster.isModuleActive(any())).thenReturn(true);
        trigger = new JurisTrackRosterTrigger(roster, new JurisTrackRosterProperties(0, 1000), false);
    }

    @AfterEach
    void tearDown() {
        trigger.stop();
    }

    @Test
    void aBurstOfChangesInOneBusinessIsOnePushAndEachBusinessGetsItsOwn() throws Exception {
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        for (int i = 0; i < 20; i++) trigger.rosterChanged(first);
        trigger.rosterChanged(second);
        verify(roster, timeout(3000).times(1)).syncBusiness(first);
        verify(roster, timeout(3000).times(1)).syncBusiness(second);
        Thread.sleep(1500);
        verify(roster, times(1)).syncBusiness(first);
        verify(roster, times(1)).syncBusiness(second);
        // a later change is a new push
        trigger.rosterChanged(first);
        verify(roster, timeout(3000).times(2)).syncBusiness(first);
    }

    @Test
    void aBusinessWithoutTheModuleIsNeverPushed() throws Exception {
        UUID business = UUID.randomUUID();
        when(roster.isModuleActive(business)).thenReturn(false);
        trigger.rosterChanged(business);
        verify(roster, timeout(3000)).isModuleActive(business);
        Thread.sleep(300);
        verify(roster, never()).syncBusiness(any());
    }

    @Test
    void withTheGatewayOffNothingIsEvenScheduled() throws Exception {
        when(roster.isGatewayOn()).thenReturn(false);
        trigger.rosterChanged(UUID.randomUUID());
        Thread.sleep(600);
        verify(roster, never()).isModuleActive(any());
        verify(roster, never()).syncBusiness(any());
    }

    @Test
    void aFailingPushIsSwallowed() throws Exception {
        UUID business = UUID.randomUUID();
        when(roster.syncBusiness(business)).thenThrow(new ApiException(HttpStatus.BAD_GATEWAY, "JURISTRACK_UNAVAILABLE", "down"));
        trigger.rosterChanged(business);
        verify(roster, timeout(3000).times(1)).syncBusiness(business);
        // the executor still works afterwards
        org.mockito.Mockito.doThrow(new IllegalStateException("boom")).when(roster).syncBusiness(business);
        trigger.rosterChanged(business);
        verify(roster, timeout(3000).times(2)).syncBusiness(business);
    }

    @Test
    void theFullResyncPushesEveryBusinessWithTheModuleAndStopsWhenJurisTrackIsUnreachable() {
        UUID first = UUID.randomUUID(), second = UUID.randomUUID(), third = UUID.randomUUID();
        when(roster.businessesWithModule()).thenReturn(List.of(first, second, third));
        when(roster.syncBusiness(second)).thenThrow(new ApiException(HttpStatus.BAD_GATEWAY, "JURISTRACK_REFUSED", "no"));
        org.assertj.core.api.Assertions.assertThat(trigger.syncAllBusinesses()).isEqualTo(2); // first and third; a refusal does not stop the run
        when(roster.syncBusiness(third)).thenThrow(new ApiException(HttpStatus.BAD_GATEWAY, "JURISTRACK_UNAVAILABLE", "down"));
        org.assertj.core.api.Assertions.assertThat(trigger.syncAllBusinesses()).isEqualTo(1);
        verify(roster, times(2)).syncBusiness(first);
        verify(roster, times(2)).syncBusiness(third);
    }
}
