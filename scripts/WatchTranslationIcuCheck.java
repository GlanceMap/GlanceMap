import com.ibm.icu.number.NumberFormatter;
import com.ibm.icu.number.Precision;
import com.ibm.icu.text.MessagePattern;
import com.ibm.icu.text.NumberFormat;
import com.ibm.icu.text.PluralFormat;
import com.ibm.icu.text.PluralRules;
import com.ibm.icu.util.ULocale;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

/** Host-side ICU checks only: no dependency is added to the Android application. */
class WatchTranslationIcuCheck {
    private static final double[] DISTANCES = {0, 1, 1.0, 1.5, 2, 2.5, 5, 12, 21, 22, 25, 101, 102};

    public static void main(String[] args) throws Exception {
        Set<String> languages = Arrays.stream(ULocale.getAvailableLocales())
            .map(ULocale::getLanguage).collect(Collectors.toSet());
        int patterns = 0, plurals = 0, rendered = 0;
        for (String line : Files.readAllLines(Path.of(args[0]), StandardCharsets.UTF_8)) {
            String[] row = line.split("\t", -1);
            require(row.length == 4, "Invalid validation manifest row");
            new java.util.Locale.Builder().setLanguageTag(row[1]).build();
            ULocale locale = ULocale.createCanonical(ULocale.forLanguageTag(row[1]).toString());
            require(languages.contains(locale.getLanguage()), "Unknown ICU language: " + row[1]);
            PluralRules rules = PluralRules.forLocale(locale);
            String label = row[1] + "/" + row[2];
            if (row[0].equals("plural")) {
                Set<String> actual = new HashSet<>(Arrays.asList(row[3].split(",")));
                require(actual.equals(rules.getKeywords()), label + ": CLDR quantities expected " + rules.getKeywords() + ", found " + actual);
                plurals++;
                continue;
            }
            require(row[0].equals("icu"), "Unknown manifest record type");
            String pattern = new String(Base64.getDecoder().decode(row[3]), StandardCharsets.UTF_8);
            checkPattern(pattern, rules, locale, label);
            PluralFormat spoken = new PluralFormat(locale);
            spoken.applyPattern(pattern);
            for (double distance : DISTANCES) {
                for (int digits : new int[]{0, 1}) {
                    NumberFormat number = NumberFormat.getNumberInstance(locale);
                    number.setGroupingUsed(false);
                    number.setMinimumFractionDigits(digits);
                    number.setMaximumFractionDigits(digits);
                    spoken.setNumberFormat(number);
                    String result = spoken.format(distance);
                    require(result.contains(number.format(distance)), label + ": formatted distance lost at " + distance + "/" + digits);
                    require(result.indexOf('#') < 0 && result.indexOf('{') < 0 && result.indexOf('}') < 0,
                        label + ": unexpanded ICU syntax in spoken output");
                    rendered++;
                }
            }
            patterns++;
        }
        System.out.println("PASS ICU: " + plurals + " locale-aware plural resources, " + patterns
            + " parsed spoken-distance patterns, " + rendered + " decimal/integer rendering cases");
    }

    private static void checkPattern(String text, PluralRules rules, ULocale locale, String label) {
        MessagePattern parsed = new MessagePattern().parsePluralStyle(text);
        Set<String> selectors = new HashSet<>();
        for (int i = 0; i < parsed.countParts(); i++) {
            MessagePattern.Part part = parsed.getPart(i);
            require(part.getType() != MessagePattern.Part.Type.ARG_INT && part.getType() != MessagePattern.Part.Type.ARG_DOUBLE,
                label + ": distance patterns must not introduce offsets or exact-value overrides");
            if (part.getType() != MessagePattern.Part.Type.ARG_SELECTOR) continue;
            String selector = parsed.getSubstring(part);
            require(selectors.add(selector), label + ": duplicate ICU selector " + selector);
            require(rules.getKeywords().contains(selector), label + ": selector is not a plural category for this locale: " + selector);
            int end = parsed.getLimitPartIndex(i + 1);
            int replacements = 0;
            for (int j = i + 2; j < end; j++) {
                if (parsed.getPart(j).getType() == MessagePattern.Part.Type.REPLACE_NUMBER) replacements++;
            }
            require(replacements == 1, label + ": every plural phrase must retain exactly one distance #");
        }
        Set<String> required = new HashSet<>();
        required.add("other");
        // Existing Romance patterns validly use other for the rare million category.
        // Require every category exercised by ordinary distances, including fractions.
        for (int count = 0; count <= 200; count++) required.add(rules.select(count));
        for (double distance : DISTANCES) {
            for (int digits : new int[]{0, 1}) {
                required.add(rules.select(NumberFormatter.withLocale(locale)
                    .precision(Precision.fixedFraction(digits)).format(distance)));
            }
        }
        require(selectors.containsAll(required), label + ": missing distance selectors " + difference(required, selectors));
    }

    private static Set<String> difference(Set<String> left, Set<String> right) {
        Set<String> result = new HashSet<>(left);
        result.removeAll(right);
        return result;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
