package com.bigbazaar.identity.kyc;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Decides whether two spellings of a name belong to the same person or business. Registries disagree
 * on titles ("Shri", "M/s"), company suffixes ("Pvt Ltd" vs "Private Limited"), word order and initials
 * ("K Sharma" vs "Kai Sharma"), so names are normalised to words and compared word by word.
 *
 * <p>A single letter matches a word starting with it. The score averages how much of each name was
 * matched, so a missing middle name costs a little and a missing surname costs a lot:
 * "Kai Kumar Sharma" vs "Kai Sharma" scores 0.83, "Kai" vs "Kai Sharma" 0.75, identical names 1.0.
 */
public final class NameMatcher {

    private static final Set<String> NOISE = Set.of("MR", "MRS", "MS", "MISS", "DR", "SHRI", "SRI", "SMT", "KUM",
            "MESSRS", "THE", "AND", "PVT", "PRIVATE", "LTD", "LIMITED", "LLP", "CO", "COMPANY",
            "OPC", "PROP", "PROPRIETOR", "HUF");

    private NameMatcher() {
    }

    public static double score(String a, String b) {
        List<String> left = words(a);
        List<String> right = words(b);
        if (left.isEmpty() || right.isEmpty()) {
            return 0;
        }
        List<String> shorter = left.size() <= right.size() ? left : right;
        List<String> longer = new ArrayList<>(left.size() <= right.size() ? right : left);
        int matched = 0;
        for (String word : shorter) {
            String hit = longer.stream().filter(word::equals).findFirst()
                    .or(() -> longer.stream().filter(other -> initialOf(word, other)).findFirst())
                    .orElse(null);
            if (hit != null) {
                longer.remove(hit);
                matched++;
            }
        }
        return ((double) matched / shorter.size() + (double) matched / Math.max(left.size(), right.size())) / 2;
    }

    public static boolean matches(String a, String b, double threshold) {
        return score(a, b) >= threshold;
    }

    static List<String> words(String name) {
        if (name == null) {
            return List.of();
        }
        String ascii = Normalizer.normalize(name, Normalizer.Form.NFKD).replaceAll("\\p{M}", "");
        String cleaned = ascii.toUpperCase(Locale.ROOT).replaceAll("\\bM\\s*/\\s*S\\b\\.?", " ")
                .replace("&", " AND ").replaceAll("[^A-Z0-9 ]", " ");
        List<String> words = new ArrayList<>();
        for (String word : cleaned.trim().split("\\s+")) {
            if (!word.isEmpty() && !NOISE.contains(word)) {
                words.add(word);
            }
        }
        return words;
    }

    private static boolean initialOf(String a, String b) {
        return (a.length() == 1 && b.startsWith(a)) || (b.length() == 1 && a.startsWith(b));
    }
}
