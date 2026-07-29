package com.hhovhann.hivemind.core.entity;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * One human, however many names they answer to.
 *
 * <p>Identity is keyed on email where a system provides one, because email is the
 * only identifier that actually crosses Slack, Notion and Zoom. Names do not: two
 * people here are called Alex, and a resolver that keys on given names merges the VP
 * of Content with a staff engineer and then hands each of them the other's work.
 *
 * @param id            stable identity, derived from email or from the canonical name
 * @param canonicalName the name to show a human
 * @param email         work address, when a source knew one
 * @param title         role, useful for weighing authority
 * @param aliases       every name this person has been seen under
 */
public record Person(String id, String canonicalName, String email, String title, Set<Alias> aliases) {

    public Person {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(canonicalName, "canonicalName");
        aliases = Set.copyOf(aliases);
    }

    public static String idFor(String email, String canonicalName) {
        String basis = email == null || email.isBlank()
                ? "name:" + normalise(canonicalName)
                : "email:" + email.strip().toLowerCase(Locale.ROOT);
        return "person:" + basis;
    }

    public Person withAlias(Alias alias) {
        Set<Alias> merged = new LinkedHashSet<>(aliases);
        merged.add(alias);
        return new Person(id, canonicalName, email, title, merged);
    }

    public boolean answersTo(String name) {
        if (name == null) {
            return false;
        }
        String candidate = normalise(name);
        return normalise(canonicalName).equals(candidate)
                || aliases.stream().anyMatch(alias -> normalise(alias.value()).equals(candidate));
    }

    public Optional<String> emailIfPresent() {
        return Optional.ofNullable(email).filter(value -> !value.isBlank());
    }

    /** Lowercase, punctuation-free form used for comparison only. */
    public static String normalise(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]", "").strip();
    }
}
