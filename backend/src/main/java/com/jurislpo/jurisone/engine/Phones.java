package com.jurislpo.jurisone.engine;

import java.util.List;
import java.util.Optional;

/** Country codes for phone numbers and how many digits the national number has. */
public final class Phones {
    private Phones() {}

    /** {@code firstDigits}: allowed first digits of the national number ("" = any). */
    public record Country(String code, String name, int digits, String firstDigits) {}

    public static final List<Country> COUNTRIES = List.of(
            new Country("+91", "India", 10, "6789"),
            new Country("+1", "United States / Canada", 10, "23456789"),
            new Country("+44", "United Kingdom", 10, "123789"),
            new Country("+971", "United Arab Emirates", 9, "5"),
            new Country("+966", "Saudi Arabia", 9, "5"),
            new Country("+974", "Qatar", 8, "3567"),
            new Country("+65", "Singapore", 8, "689"),
            new Country("+61", "Australia", 9, "45"),
            new Country("+977", "Nepal", 10, "9"),
            new Country("+880", "Bangladesh", 10, "1"),
            new Country("+94", "Sri Lanka", 9, "7"),
            new Country("+27", "South Africa", 9, "678"),
            new Country("+353", "Ireland", 9, "8"),
            new Country("+33", "France", 9, "67"),
            new Country("+81", "Japan", 10, "789"));

    public static Optional<Country> country(String code) {
        return COUNTRIES.stream().filter(c -> c.code().equals(code)).findFirst();
    }

    /** Problem with this number for the country, or null when it is valid. */
    public static String problem(String code, String number) {
        Country c = country(code).orElse(null);
        if (c == null) return "Choose the country code";
        String d = number == null ? "" : number.replaceAll("\\D", "");
        if (d.length() != c.digits()) return c.name() + " numbers have " + c.digits() + " digits";
        if (!c.firstDigits().isEmpty() && c.firstDigits().indexOf(d.charAt(0)) < 0) return "That isn't a valid " + c.name() + " mobile number";
        return null;
    }
}
