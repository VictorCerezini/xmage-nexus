package mage.client.bridge.processor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Chooses the mana source the bridge taps for a "Pay ..." prompt. The bridge only taps plain sources, whose
 * whole cost is {T}: a source that also needs mana, a sacrifice or life (filter lands, Signets, Treasure, Gold)
 * is left to the model, which can plan around it. Colors are checked, so a land is not spent on a symbol it
 * cannot pay, or on generic mana while a colored symbol still needs it.
 */
final class BridgeManaSources {
    private static final Pattern SYMBOL = Pattern.compile("\\{([^}]+)}");
    /** The effect part of a mana ability: "cost: Add ...". */
    private static final Pattern MANA_ABILITY = Pattern.compile(":\\s*Add\\b");
    private static final String COLORS = "WUBRG";
    private static final String MANA_LETTERS = "WUBRGC";
    private static final int SEARCH_LIMIT = 20_000;

    /** A plain source: the mana letters it can make and how much mana one tap gives. */
    record Source(UUID id, String colors, int units) {
    }

    /** The unpaid cost of a prompt: the letters that pay each colored symbol, and the generic amount. */
    record Cost(List<String> colored, int generic) {
    }

    private record Unit(int source, String colors) {
    }

    private BridgeManaSources() {
    }

    /**
     * True when every choice of an ability prompt is a mana ability ("{T}: Add {C}.", "{B/R}, {T}: Add {B}{B}.").
     * Mode choices ("Boros Charm deals 4 damage ...") and other ability choices are not.
     */
    static boolean onlyManaAbilities(Collection<String> choices) {
        if (choices == null || choices.isEmpty()) {
            return false;
        }
        for (String choice : choices) {
            if (choice == null || !MANA_ABILITY.matcher(choice).find()) {
                return false;
            }
        }
        return true;
    }

    /** The cost in a "Pay {1}{B}{B}<div>...</div>" prompt, or null when a symbol is not understood. */
    static Cost parseCost(String prompt) {
        if (prompt == null) {
            return null;
        }
        int html = prompt.indexOf('<');
        String text = html >= 0 ? prompt.substring(0, html) : prompt;
        List<String> colored = new ArrayList<>();
        int generic = 0;
        boolean found = false;
        Matcher matcher = SYMBOL.matcher(text);
        while (matcher.find()) {
            found = true;
            String symbol = matcher.group(1).trim().toUpperCase(Locale.ROOT);
            if (!symbol.isEmpty() && symbol.chars().allMatch(Character::isDigit)) {
                generic += Integer.parseInt(symbol);
                continue;
            }
            if (symbol.equals("X")) {
                continue;
            }
            if (symbol.equals("S")) {
                // snow mana needs a snow source, which the ability text does not show
                return null;
            }
            var letters = new StringBuilder();
            for (String part : symbol.split("/")) {
                // {W/P} is paid with W (life is chosen before payment), {2/W} with W
                if (part.equals("P") || (!part.isEmpty() && part.chars().allMatch(Character::isDigit))) {
                    continue;
                }
                if (part.length() != 1 || MANA_LETTERS.indexOf(part.charAt(0)) < 0) {
                    return null;
                }
                letters.append(part);
            }
            if (letters.isEmpty()) {
                return null;
            }
            colored.add(letters.toString());
        }
        return found ? new Cost(colored, generic) : null;
    }

    /** The letters a mana ability makes when its whole cost is {T}, or null when it is not plain. */
    static String plainColors(String ability) {
        String effect = plainEffect(ability);
        if (effect == null) {
            return null;
        }
        String lower = effect.toLowerCase(Locale.ROOT);
        if (lower.contains("any type")) {
            return MANA_LETTERS;
        }
        if (lower.contains("any color") || lower.contains("any one color") || lower.contains("combination of colors")) {
            return COLORS;
        }
        var letters = new StringBuilder();
        Matcher matcher = SYMBOL.matcher(firstSentence(effect));
        while (matcher.find()) {
            String symbol = matcher.group(1).trim();
            if (symbol.length() == 1 && MANA_LETTERS.contains(symbol) && letters.indexOf(symbol) < 0) {
                letters.append(symbol);
            }
        }
        // a plain ability whose mana is not written as symbols: assume it can pay anything
        return letters.isEmpty() ? MANA_LETTERS : letters.toString();
    }

    /** How much mana one tap of a plain ability gives: {C}{C} gives two, "{W} or {B}" gives one. */
    static int plainUnits(String ability) {
        String effect = plainEffect(ability);
        if (effect == null) {
            return 0;
        }
        String sentence = firstSentence(effect);
        String lower = sentence.toLowerCase(Locale.ROOT);
        if (lower.contains(" or ") || lower.contains("any ") || lower.contains("combination")) {
            return 1;
        }
        int symbols = 0;
        Matcher matcher = SYMBOL.matcher(sentence);
        while (matcher.find()) {
            symbols++;
        }
        return Math.max(1, symbols);
    }

    private static String plainEffect(String ability) {
        if (ability == null) {
            return null;
        }
        int colon = ability.indexOf(':');
        if (colon < 0 || !ability.substring(0, colon).trim().equals("{T}")) {
            return null;
        }
        String effect = ability.substring(colon + 1);
        // "Spend this mana only to ..." (the text can be cut short, so any "spend" counts)
        if (effect.toLowerCase(Locale.ROOT).contains("spend")) {
            return null;
        }
        return effect;
    }

    private static String firstSentence(String effect) {
        int end = effect.indexOf('.');
        return end >= 0 ? effect.substring(0, end) : effect;
    }

    /**
     * The source to tap next so that the plain sources still pay the whole cost, or null when they cannot.
     * Each colored symbol gets its own mana, the symbol with the fewest choices first, from the source with the
     * fewest colors; generic takes what is left.
     */
    static UUID nextToTap(Cost cost, List<Source> sources) {
        List<Unit> units = units(sources);
        List<String> needs = sortedNeeds(cost, units);
        int[] assigned = new int[needs.size()];
        boolean[] used = new boolean[units.size()];
        int[] steps = {0};
        if (!assign(needs, 0, units, used, assigned, cost.generic(), steps)) {
            return null;
        }
        if (!needs.isEmpty()) {
            return sources.get(units.get(assigned[0]).source()).id();
        }
        Unit plainest = fewestColors(units, null, used);
        return plainest == null ? null : sources.get(plainest.source()).id();
    }

    /** When the plain sources cannot pay it all: a source that pays one symbol, or generic mana, if any. */
    static UUID bestEffort(Cost cost, List<Source> sources) {
        List<Unit> units = units(sources);
        boolean[] used = new boolean[units.size()];
        for (String need : sortedNeeds(cost, units)) {
            Unit unit = fewestColors(units, need, used);
            if (unit != null) {
                return sources.get(unit.source()).id();
            }
        }
        if (cost.generic() > 0) {
            Unit unit = fewestColors(units, null, used);
            if (unit != null) {
                return sources.get(unit.source()).id();
            }
        }
        return null;
    }

    private static List<Unit> units(List<Source> sources) {
        var units = new ArrayList<Unit>();
        for (int i = 0; i < sources.size(); i++) {
            for (int u = 0; u < sources.get(i).units(); u++) {
                units.add(new Unit(i, sources.get(i).colors()));
            }
        }
        return units;
    }

    private static List<String> sortedNeeds(Cost cost, List<Unit> units) {
        var needs = new ArrayList<>(cost.colored());
        needs.sort(Comparator.comparingLong(need -> units.stream().filter(unit -> pays(unit, need)).count()));
        return needs;
    }

    private static boolean assign(
            List<String> needs, int next, List<Unit> units, boolean[] used, int[] assigned, int generic, int[] steps) {
        if (++steps[0] > SEARCH_LIMIT) {
            return false;
        }
        if (next == needs.size()) {
            int free = 0;
            for (boolean taken : used) {
                if (!taken) {
                    free++;
                }
            }
            return free >= generic;
        }
        var candidates = new ArrayList<Integer>();
        for (int u = 0; u < units.size(); u++) {
            if (!used[u] && pays(units.get(u), needs.get(next))) {
                candidates.add(u);
            }
        }
        candidates.sort(Comparator.comparingInt(u -> units.get(u).colors().length()));
        for (int u : candidates) {
            used[u] = true;
            assigned[next] = u;
            if (assign(needs, next + 1, units, used, assigned, generic, steps)) {
                return true;
            }
            used[u] = false;
        }
        return false;
    }

    private static Unit fewestColors(List<Unit> units, String need, boolean[] used) {
        Unit best = null;
        for (int u = 0; u < units.size(); u++) {
            Unit unit = units.get(u);
            if (used[u] || (need != null && !pays(unit, need))) {
                continue;
            }
            if (best == null || unit.colors().length() < best.colors().length()) {
                best = unit;
            }
        }
        return best;
    }

    private static boolean pays(Unit unit, String need) {
        for (int i = 0; i < need.length(); i++) {
            if (unit.colors().indexOf(need.charAt(i)) >= 0) {
                return true;
            }
        }
        return false;
    }
}
