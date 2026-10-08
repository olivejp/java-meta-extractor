package fr.cafat.meta.config;

/** Valeur de configuration brute avec sa provenance. */
public record ConfigEntry(String key, String value, String file, Integer line) {
}
