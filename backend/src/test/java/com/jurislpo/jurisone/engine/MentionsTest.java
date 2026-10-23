package com.jurislpo.jurisone.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("@mentions")
class MentionsTest {

    @Test
    @DisplayName("finds each mentioned person once, in order")
    void finds() {
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        String text = "Hi @{" + first + "}, please check with @{" + second + "} and @{" + first + "} again.";
        assertThat(Mentions.find(text)).containsExactly(first, second);
    }

    @Test
    @DisplayName("ignores plain @names, broken tokens and empty text")
    void ignores() {
        assertThat(Mentions.find("mail me @riya or @{not-a-uuid} or @" + UUID.randomUUID())).isEmpty();
        assertThat(Mentions.find(null)).isEmpty();
        assertThat(Mentions.find("")).isEmpty();
    }
}
