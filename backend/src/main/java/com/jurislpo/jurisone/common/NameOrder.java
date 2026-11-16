package com.jurislpo.jurisone.common;

import java.text.Collator;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * The one ordering for people's names (and any other text a list is sorted by in memory): the same order the database gives with
 * {@link #SQL_COLLATION} (ICU, root rules), so a list does not change order when the encrypted-names switch is turned on.
 * <ol>
 * <li>Letters ignoring case and accents, with space, punctuation and digits before letters (space first, then {@code _ - , ; : ! ? . ' " ( ) [ ] { } @ * / \ & # % ` ^ + < = > | ~ $}, then digits).</li>
 * <li>Accents: an unaccented letter before the accented one ("Elodie" before "Élodie").</li>
 * <li>Case: lower case before upper case ("zoya" before "Zoya").</li>
 * <li>Last, the plain character-code order, so two different texts are never "equal"; callers add the id after that.</li>
 * </ol>
 * Known difference from ICU: letters that expand or have their own place in a language (such as ß, æ, ø) are compared as the Java collator compares them.
 */
public final class NameOrder {

    /** The database collation used wherever the same order is asked of PostgreSQL (switch off); it needs an ICU-enabled PostgreSQL (the standard images and RDS have it). */
    public static final String SQL_COLLATION = "en-US-x-icu";

    private static final String PUNCTUATION_ORDER = " _-,;:!?.'\"()[]{}@*/\\&#%`^+<=>|~$";
    private static final Collator LETTERS = Collator.getInstance(Locale.ROOT);

    static {
        LETTERS.setStrength(Collator.PRIMARY);
    }

    /** Compares two non-null texts. */
    private static final Comparator<String> TEXT = NameOrder::compareText;

    /** Null first (this class has always put a missing name first in memory); used by every in-memory name sort. */
    public static final Comparator<String> INSTANCE = Comparator.nullsFirst(TEXT);

    /** Null last when ascending, as the database does ({@code ORDER BY ... ASC} puts NULL last); reverse it for descending. */
    public static final Comparator<String> NULLS_LAST = Comparator.nullsLast(TEXT);

    private NameOrder() {}

    private static int compareText(String first, String second) {
        List<Unit> firstUnits = units(first), secondUnits = units(second);
        int common = Math.min(firstUnits.size(), secondUnits.size());
        for (int i = 0; i < common; i++) {
            int result = comparePrimary(firstUnits.get(i).base(), secondUnits.get(i).base());
            if (result != 0) return result;
        }
        if (firstUnits.size() != secondUnits.size()) return Integer.compare(firstUnits.size(), secondUnits.size());
        for (int i = 0; i < common; i++) {
            String firstMarks = firstUnits.get(i).marks(), secondMarks = secondUnits.get(i).marks();
            if (!firstMarks.equals(secondMarks)) {
                if (firstMarks.isEmpty()) return -1;
                if (secondMarks.isEmpty()) return 1;
                return Integer.signum(firstMarks.compareTo(secondMarks));
            }
        }
        for (int i = 0; i < common; i++) {
            char firstBase = firstUnits.get(i).base(), secondBase = secondUnits.get(i).base();
            if (firstBase != secondBase) return Character.isLowerCase(firstBase) ? -1 : 1;
        }
        return Integer.signum(first.compareTo(second));
    }

    /** A letter (or other character) with the accents that follow it once the text is decomposed. */
    private record Unit(char base, String marks) {}

    private static List<Unit> units(String text) {
        String decomposed = Normalizer.normalize(text, Normalizer.Form.NFD);
        List<Unit> units = new ArrayList<>(decomposed.length());
        for (int i = 0; i < decomposed.length(); i++) {
            char character = decomposed.charAt(i);
            if (Character.getType(character) == Character.NON_SPACING_MARK && !units.isEmpty()) {
                Unit last = units.remove(units.size() - 1);
                units.add(new Unit(last.base(), last.marks() + character));
            } else {
                units.add(new Unit(character, ""));
            }
        }
        return units;
    }

    private static int comparePrimary(char first, char second) {
        if (first == second) return 0;
        int firstClass = characterClass(first), secondClass = characterClass(second);
        if (firstClass != secondClass) return Integer.compare(firstClass, secondClass);
        if (firstClass == 0) return Integer.compare(punctuationRank(first), punctuationRank(second));
        if (firstClass == 1) return Integer.compare(first, second);
        return LETTERS.compare(String.valueOf(first), String.valueOf(second));
    }

    private static int characterClass(char character) {
        if (Character.isLetter(character)) return 2;
        return Character.isDigit(character) ? 1 : 0;
    }

    private static int punctuationRank(char character) {
        int index = PUNCTUATION_ORDER.indexOf(character);
        return index >= 0 ? index : PUNCTUATION_ORDER.length() + character;
    }
}
