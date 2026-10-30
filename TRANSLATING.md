# Help translate GlanceMap

GlanceMap runs on phones and very small watch displays. For watch translations,
**when two translations are equally accurate and natural, prefer the shorter
wording.** On both devices, preserve meaning first, then natural language and
clarity. Avoid cryptic abbreviations.

GitHub is the authoritative source. Community changes go through translation
checks, a GitHub pull request, and maintainer review before merging.

## Translate with Weblate — preferred

Use the [GlanceMap project on Hosted Weblate](https://hosted.weblate.org/projects/glancemap/).
The Wear OS component and glossary are available there. Phone components are
still being prepared in the repository and are not yet connected.

To contribute:

1. Open GlanceMap on Weblate and choose your language.
2. Translate or review a string. Read its context, comments, and glossary terms.
3. Submit the translation. If the meaning is unclear, ask in the string's comments.
4. Weblate checks it and sends changes through a GitHub translation PR.
5. GlanceMap localization CI checks the resources; a maintainer reviews and merges.

You do not need Android Studio, Git, XML knowledge, Gradle, or resource IDs.
Existing German, Spanish, French, Italian, and Polish wording is approved source
material for the initial import. Do not replace it with machine translations.

The [glossary](docs/translation-glossary.md) explains recurring terms. Screenshots
and discussion are useful when a short English label has several possible meanings.

## Report incorrect wording

**Setup pending:** GitHub publishes issue forms from the default branch. The
[translation / wording correction form](https://github.com/GlanceMap/GlanceMap/issues/new?template=translation_correction.yml)
becomes available after this infrastructure is merged into `main`.
Until then, [open a normal GitHub issue](https://github.com/GlanceMap/GlanceMap/issues/new)
and include the language, where the wording appears, the current wording and your
suggestion. Drag in a screenshot if useful. The same information is requested by
the form once it is live. No resource ID, debug log, or developer tools are needed.
Maintainers can locate the string and apply/review a correction.

## Direct GitHub contribution

Experienced contributors may edit `app/src/main/res/values-<locale>/strings.xml`
for the watch or the matching files in `translations/phone/values-<locale>/`
for phone drafts, and open a focused PR. Weblate remains preferred because it supplies shared
context, review discussion, and translation checks.

- Use `app/src/main/res/values/strings.xml` for watch English. Phone English is
  in the corresponding file under `glancemapcompanionapp/src/main/res/values/`.
- Leave `translatable="false"` names and technical values in the base file.
- Preserve positional argument types, indexes and precision, such as `%1$s`,
  `%2$d`, `%1$.1f`, and `%%`. Arguments may be reordered, not dropped or added.
  Java format checking supports the app's positional `s`, `d` and `f` conversions,
  valid width/precision/flags, plus `%%` and `%n`. Implicit/relative argument
  indexing and unsupported conversions are rejected. Plain percentage labels
  such as “100%” and ICU patterns are not interpreted as Java format strings.
- Preserve annotations, XML markup, intentional `\n`, whitespace, and units.
- Plural categories belong to the target language, not English. Translate the
  complete `<plurals>` resource, including its required `other` form.
- The three spoken-distance patterns use ICU **PluralFormat** syntax. Keep the
  distance `#`, decimal meaning, and the distinction between “for” and “in”. They
  are not complete ICU MessageFormat messages; do not wrap or rewrite their syntax.
- Keep GlanceMap, OpenStreetMap, Mapsforge, BRouter, Refuges.info, GPX and GPS
  unchanged where they are names or intentional technical terms.
- Do not change layout, text size, wrapping, map/navigation logic, or recording
  behavior to make a translation fit. Flag long text for a physical-watch check.

For local validation, use Python 3.9 or newer and JDK 17:

```sh
scripts/check-watch-translations.sh --report-dir build/reports/translations
python3 -m unittest discover -s scripts/tests -p 'test_watch_translations.py'
./gradlew --no-daemon --no-configuration-cache :app:processDebugResources :app:compileDebugAndroidTestKotlin :app:lintDebug
git diff --check
```

The wrapper downloads a checksum-pinned ICU4J validation tool into the user cache;
it does not add a dependency to the app. Set `ICU4J_JAR` to that verified JAR when
running the JVM regression tests locally. CI sets it and runs the full suite.
`--structure-only` is a clearly marked diagnostic option; it is not a substitute
for the full check and is never used by CI.

The canonical validator discovers locales, including Android regional and script
qualifiers. It ignores display-only directories such as `values-round`,
`values-night`, and `values-v31`. Configuration-specific language overrides are
checked as overrides, rather than as separate languages.

## Phone translations in progress

The phone uses all translatable resources across `glancemapcompanionapp/src/main/res/values/*.xml`
as its English source. German, Spanish, French, Italian, and Polish draft files in
`translations/phone/values-<locale>/` currently reuse matching watch wording.
These review drafts are outside the Android resources; the phone remains in
English until a complete, reviewed language is promoted into the app.
These drafts are incomplete and must not be treated as release-ready. In particular,
navigation, recording, safety and destructive-action wording requires review.

The phone uses the same format, markup and ICU/CLDR checks as the watch. To check
work in progress, run:

```sh
bash scripts/check-watch-translations.sh --phone --allow-incomplete --report-dir build/reports/translations/phone
python3 -m unittest discover -s scripts/tests -p 'test_phone_translations.py'
```

Omit `--allow-incomplete` for the strict release check; it fails until every phone
resource has a translation. Localization CI validates incomplete drafts for
structure; the strict check is required before promotion. The profile language
picker currently offers system default and English. Add a language to the picker
and Android locale configuration only after its translation is complete and
reviewed, as on the watch. Checks do not prove linguistic correctness
or screen fit.

## Watch readability

The length audit exports English/translated character counts and expansion to
`length-audit-<Android-locale>.csv`. Character counts are a screening aid, not
pixel measurements. For short UI text, about 20–25% expansion deserves review;
large expansion or a long title/control needs a physical-watch check. Expansion
warnings alone never fail CI. Do not distort grammar to satisfy a percentage.
Spoken ICU patterns are excluded from this visual audit: extra plural branches
are syntax, not display width. Their syntax and rendering still receive full
ICU validation.

## Add a language

A translator chooses or requests a new language in Weblate. Weblate creates the
Android locale file and adds strings as they are translated; contributors do not
copy all English strings by hand or need repository write access. The same
validator and workflow discover the new file automatically.

Resource completeness is a merge gate: an incomplete translation PR can stay in
progress while Weblate accumulates work. Merge a new UI language when all source
entries are present and reviewed, critical navigation/warnings/destructive
actions are translated, placeholders/plurals/ICU/markup pass, and representative
watch screens have been checked. Weblate completion statistics are useful
progress indicators, not evidence of linguistic correctness.

This is a documented release policy, not a new arbitrary percentage cutoff.
Existing supported languages are retained. One shared English fallback serves
English-speaking countries; do not create redundant country-specific English files.

A complete new locale file passes validation without editing a language list in
the validator or workflows. Advertising it in the app's existing picker and
`locales_config.xml` is a separate maintainer release decision. Those production
settings are not automatically changed by Weblate.

## Legal, safety and credits

**Legal, safety, licensing and attribution translations require maintainer review
before merging.** This includes `raw-*/legal_*.md` and UI strings that present legal
or safety information. Do not change license statements, warnings, URLs, quoted
identifiers, dates, hashes, or provider names through a wording cleanup.
Keep legal changes separate from routine UI wording batches and request an
explicit maintainer review of warning meaning, licensing and attribution;
language fluency alone does not establish those obligations.

Existing translated documents are preserved. A new UI language may initially use
the English document fallback; no duplicate English legal files are required.
Once a localized legal bundle is supplied, it must contain all source documents
and pass the document integrity checks. Send wording suggestions through the
same issue form or a separate, explicitly reviewed PR.

The planned **Legal / Credits** Weblate area is separate from **Wear OS App**.
It is not activated using the Markdown backend, which cannot safely import our
existing translated Markdown as authoritative content. See the
[maintainer setup and import-safety instructions](docs/weblate-setup.md).

## Maintainers

See [Hosted Weblate setup](docs/weblate-setup.md) for the component settings,
per-language import checklist, owner authorization boundaries, PR protection,
and glossary import. Production synchronization targets `main` only after the
localization resources and this workflow have reached it through normal review.
