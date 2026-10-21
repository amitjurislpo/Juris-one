package com.jurislpo.jurisone.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Old-regime declaration items (REQUIREMENTS §5.10). */
public final class TaxCatalogue {
    private TaxCatalogue() {}

    record Item(String key, String label, boolean auto) {}

    record Section(String label, String key, List<Item> items) {}

    static final List<Section> DECL = List.of(
            new Section("HRA", "HRA", List.of(new Item("rent", "Annual rent paid", false))),
            new Section("80C", "80C", List.of(new Item("epf", "Employee provident fund (auto)", true), new Item("ppf", "Public provident fund (PPF)", false),
                    new Item("elss", "ELSS mutual funds", false), new Item("lic", "Life insurance premium", false), new Item("ssy", "Sukanya Samriddhi Yojana", false),
                    new Item("tuition", "Children’s tuition fees", false), new Item("hlp", "Home loan principal", false), new Item("nsc", "NSC / 5-year tax-saving FD", false))),
            new Section("80CCD(1B)", "80CCD1B", List.of(new Item("nps", "NPS contribution (Tier I)", false))),
            new Section("80D", "80D_SELF", List.of(new Item("medSelf", "Self, spouse & children", false), new Item("medParents", "Parents", false))),
            new Section("24(b)", "24B", List.of(new Item("hli", "Interest on housing loan", false))),
            new Section("80E", "80E", List.of(new Item("edu", "Interest paid this year", false))),
            new Section("80G", "80G", List.of(new Item("don", "Eligible donation amount", false))),
            new Section("80TTA", "80TTA", List.of(new Item("sav", "Savings interest earned", false))));

    /** item key → section key stored on declaration_items (medParents → 80D_PARENTS). */
    public static final Map<String, String> ITEM_SECTION = new LinkedHashMap<>();
    public static final Map<String, String> ITEM_LABEL = new LinkedHashMap<>();
    public static final Map<String, String> SECTION_OF_ITEM = new LinkedHashMap<>();

    static {
        for (Section section : DECL) {
            for (Item i : section.items()) {
                ITEM_LABEL.put(i.key(), i.label());
                SECTION_OF_ITEM.put(i.key(), section.label());
                if (!i.auto()) ITEM_SECTION.put(i.key(), "medParents".equals(i.key()) ? "80D_PARENTS" : section.key());
            }
        }
    }

    public static boolean declarable(String key) {
        return ITEM_SECTION.containsKey(key);
    }
}
