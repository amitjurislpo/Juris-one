package com.jurislpo.jurisone.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The in-memory name order: letters first, then accents, then case, with a fixed answer for every text. */
class NameOrderTest {

    private static List<String> sorted(String... names) {
        List<String> list = new ArrayList<>(java.util.Arrays.asList(names));
        Collections.reverse(list); // start from a different order than the expected one
        list.sort(NameOrder.INSTANCE);
        return list;
    }

    @Test
    void lettersCountBeforeCaseAndAccents() {
        assertThat(sorted("zoya", "Aarav", "anita", "Meera")).containsExactly("Aarav", "anita", "Meera", "zoya");
        assertThat(sorted("Eloise", "Élodie", "Elodie")).containsExactly("Elodie", "Élodie", "Eloise");
    }

    @Test
    void sameLettersLowerCaseFirstThenAnythingElseByCharacterCode() {
        assertThat(sorted("ZOYA", "Zoya", "zoya")).containsExactly("zoya", "Zoya", "ZOYA");
        assertThat(sorted("élodie", "Élodie", "Elodie", "elodie")).containsExactly("elodie", "Elodie", "élodie", "Élodie");
    }

    @Test
    void spaceBeforeHyphenBeforeLettersAndTheShorterTextFirst() {
        assertThat(sorted("JoshiRao", "Joshi-Rao", "Joshi Rao")).containsExactly("Joshi Rao", "Joshi-Rao", "JoshiRao");
        assertThat(sorted("Zoya Khanna", "Zoya Khan-Shah", "Zoya Khan")).containsExactly("Zoya Khan", "Zoya Khan-Shah", "Zoya Khanna");
        assertThat(sorted("Mary Oneil", "Mary O'Neil")).containsExactly("Mary O'Neil", "Mary Oneil");
    }

    @Test
    void aMissingNameIsFirstAndNullsLastIsAvailableForTheDatabaseLikeRanking() {
        assertThat(sorted("b", null, "a")).containsExactly(null, "a", "b");
        List<String> list = new ArrayList<>(java.util.Arrays.asList("b", null, "a"));
        list.sort(NameOrder.NULLS_LAST);
        assertThat(list).containsExactly("a", "b", null);
    }
}
