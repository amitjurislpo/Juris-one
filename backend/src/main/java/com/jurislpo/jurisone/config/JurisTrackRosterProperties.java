package com.jurislpo.jurisone.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * When the roster goes to JurisTrack ({@code jurisone.juristrack.*}, next to {@link JurisTrackProperties}).
 *
 * @param rosterSyncMinutes    a full resync of every business with the module, this often (default 360); 0 turns the schedule and the startup sync off
 * @param rosterDebounceMillis how long a change waits for more changes of the same business before the roster is pushed (default 2000)
 */
@ConfigurationProperties(prefix = "jurisone.juristrack")
public record JurisTrackRosterProperties(int rosterSyncMinutes, long rosterDebounceMillis) {

    public JurisTrackRosterProperties {
        if (rosterSyncMinutes < 0) rosterSyncMinutes = 0;
        if (rosterDebounceMillis < 0) rosterDebounceMillis = 0;
    }
}
