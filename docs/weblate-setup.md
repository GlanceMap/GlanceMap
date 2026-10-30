# Hosted Weblate setup and import safety

Status: the [GlanceMap project](https://hosted.weblate.org/projects/glancemap/)
already exists on Hosted Weblate. On 29 October 2026 its public API listed the
Wear OS App and glossary components, but no phone component. The watch component
uses the GlanceMap GitHub repository and `main`; its five existing translations
were reported complete. This confirms public project metadata only, not the
account permissions, GitHub App authorization or outbound PR settings. Inspect
the existing project and integration before changing anything; do not create a
duplicate project or disturb the active watch component.

## Branch gate

The watch component is already connected to `main`. The phone component files
and discovery settings are currently being prepared on the TrailCore branch.
**Production phone synchronization must target `main` after those files and
checks reach it through normal maintainer review.** Do not configure a
development branch as the permanent community source. Before activating phone
components, recheck the actual project, branch and import preview. A temporary
development-branch preview must remain locked with no outbound PRs and be
verified again against `main` before production activation.

## Owner authorization boundaries

Only the owner needs to perform these account actions:

1. Sign in at [Hosted Weblate](https://hosted.weblate.org/) and open the existing
   [GlanceMap project](https://hosted.weblate.org/projects/glancemap/).
2. Check whether the project already has the required GitHub connection. If it
   does not, choose **Connect GitHub account** and install/authorize the
   official [Hosted Weblate GitHub App](https://github.com/apps/hosted-weblate)
   for the GitHub organization that owns the repository. Grant access to the
   selected repository **GlanceMap/GlanceMap** only.
3. Approve any organization installation request. If desired, submit/approve the
   [Hosted Weblate Libre hosting application](https://weblate.org/en/hosting/).
   Hosting eligibility is not assumed; approval must actually be received.

No GitHub password, pasted personal access token, deploy key, or private GitHub
App credential is required in chat or in this repository. The App-backed flow
manages repository access, notifications and PR credentials in Hosted Weblate.
Do not use the legacy webhook-only App by mistake. The remaining component,
glossary and import work can be completed by Codex after owner authorization;
it is not an additional manual burden for translators.

## Wear OS component worksheet

The following is the original setup worksheet, not a claim that every live
setting matches it. The existing watch component is currently unlocked; do
not lock, recreate or reimport it to set up the phone. Check live settings
and permissions before changing the existing integration.

| Setting | Prepared value / policy |
|---|---|
| Project | GlanceMap; suggested slug `glancemap` only if unused |
| Component | Wear OS App; suggested slug `wear-os-app` only if unused |
| Repository | `https://github.com/GlanceMap/GlanceMap.git` |
| Production source branch | `main` |
| Current repository-side phone preparation | `codex/companion-trailcore-foundation` |
| Source language | English (`en`) |
| Monolingual base language file | `app/src/main/res/values/strings.xml` |
| File mask | `app/src/main/res/values-*/strings.xml` |
| File format | Android String Resource (`aresource`) |
| Template for new translations | Empty; let Android format create translated entries |
| Language code style | Android style; preserve existing filenames |
| VCS | App-backed GitHub PR workflow: **GitHub (via Weblate GitHub app)** on current Hosted Weblate |
| Outbound translation branch | Proposed `weblate-wear-os-app`; created later by Weblate, not by this task |
| PR base | `main`; maintainer approval and localization CI required |
| Edit base file / manage source strings | Off; English source changes originate in GitHub |
| Initial outbound push for new components | Off; keep new phone components locked until import review |
| Production push | PRs only; no direct commits to `main`, no automatic merge |
| Automatic translation / bulk replacement | Off for the initial import |
| Translation license | MIT for project-owned UI translation contributions; do not relicense third-party legal statements |

The wildcard is the documented Android mask syntax. It also matches
`values-round/strings.xml`, so the component **must** use a language filter.
Use this filter for base locale files; configuration-specific overrides stay
under GitHub review and are validated by CI:

```regex
^(?!car$)(?:[a-z]{2,3}(?:-r[A-Z]{2})?|b\+[a-z]{2,3}(?:\+[A-Z][a-z]{3})?(?:\+(?:[A-Z]{2}|[0-9]{3}))?(?:\+(?:[A-Za-z0-9]{5,8}|[0-9][A-Za-z0-9]{3}))*)$
```

This admits existing languages, future regional codes such as `pt-rBR`, numeric
regions such as `b+es+419`, and script forms such as `b+sr+Latn`. The legacy `-r`
form accepts two-letter regions only; `es-r419` is invalid. The filter excludes
`round`, `night`, `v31`, `car`, and `fr-night`. Android's legacy `iw`, `in` and
`ji` map to Hebrew, Indonesian and Yiddish; Weblate must resolve their language
identity without renaming the approved files.

In the import preview, verify every match and language alias. For example,
Android `pt-rBR` corresponds to Brazilian Portuguese (`pt_BR` in Weblate), not
European Portuguese; `b+sr+Latn` is Serbian Latin. The Android filename style
controls newly generated paths. Do not rewrite existing paths just to match
Weblate's internal language codes.

The canonical references are Weblate's [Android format settings](https://docs.weblate.org/en/latest/formats/android.html),
[component settings](https://docs.weblate.org/en/latest/admin/projects.html), and
[GitHub integration](https://docs.weblate.org/en/latest/admin/code-hosting.html).
If the live Hosted UI differs, stop and verify its App-backed **PR** mode instead
of silently choosing direct Git pushes. No standalone configuration file in this
repository is automatically installed into Weblate.

## Phone app component discovery

The phone's English UI resources are split across 18 XML files. Keep those files
in place and use a separate phone group of components; do not point a single
component at only `values/strings.xml` or it will miss phone strings. The phone
drafts live in `translations/phone/values-<locale>/<source-filename>.xml`, outside
Android's shipped resources. This allows incremental, reviewed translation PRs
without releasing an incomplete mixed-language UI. The existing GlanceMap
project has no phone components yet; phone setup has not been activated.

Using the current Weblate component-discovery add-on, prepare this source-file
match. Its explicit list excludes non-translatable color, theme, and capability
files. Update the list and its coverage test if another source file is added:

```regex
^glancemapcompanionapp/src/main/res/values/(?P<component>hike|map_address_search|map_layer_status|map_online_settings|map_perspective|map_search|map_toolbar|mission_adventures|mission_stages|poi_creation|recording_activity|recording_stop|route_discovery|route_preview|route_weather|strings|strings_brouter_online|terrain_preview)\.xml$
```

| Discovery setting | Prepared value |
|---|---|
| Format | Android String Resource (`aresource`) |
| Component name template | `Phone app: {{ component }}` |
| Monolingual base template | `glancemapcompanionapp/src/main/res/values/{{ component }}.xml` |
| File mask template | `translations/phone/values-*/{{ component }}.xml` |
| Language filter | The same Android locale filter used for the watch above |
| Source language | English (`en`) |
| Repository / branch / PR mode | Same GitHub App-backed, `main`-targeting PR workflow as Wear OS |
| New translation template | Empty; let Weblate create target files |
| Remove missing components automatically | Off; review source/component removal explicitly |
| Automatic translation / bulk replacement | Off for the initial import |

The discovery add-on's file-mask mode matches the English base files, so it can
discover components even when a locale has no translated file for that component.
See the [Weblate discovery documentation](https://docs.weblate.org/en/latest/admin/addons.html#component-discovery).
Before activation, preview every source path, target mask and language mapping;
check that all five imported drafts keep their values and that an unchanged export
does not rewrite XML. The 301 watch-derived entries per locale are contextual
starting suggestions, not automatically approved phone translations. Review
meaning, placeholders, safety, navigation and destructive actions in context.

During incremental translation PRs, CI checks every submitted draft's XML,
arguments, markup, plural categories and preservation. Incomplete entries are
reported, not counted as completed translations. Before shipping any locale,
run `bash scripts/check-watch-translations.sh --phone` for strict completeness,
obtain linguistic and safety review, check representative phone screens, then
promote reviewed files into matching Android `values-<locale>` directories.
Only then add that locale to a phone language picker and platform locale config.
The strict command currently checks all draft locales together; complete all five
or isolate and validate a release candidate before promotion. Legal translations
remain separate and need the same maintainer review as on the watch.

## Existing-language import checklist

| Android locale | File detected locally | Strings | Plurals | Legal documents | Weblate mapping | Hosted import |
|---|---|---:|---:|---:|---|---|
| de | `values-de/strings.xml` | 1744 | 58 | 14 | German (`de`) | Present at 100% in public API; wording not audited |
| es | `values-es/strings.xml` | 1744 | 58 | 14 | Spanish (`es`) | Present at 100% in public API; wording not audited |
| fr | `values-fr/strings.xml` | 1744 | 58 | 14 | French (`fr`) | Present at 100% in public API; wording not audited |
| it | `values-it/strings.xml` | 1744 | 58 | 14 | Italian (`it`) | Present at 100% in public API; wording not audited |
| pl | `values-pl/strings.xml` | 1744 | 58 | 14 | Polish (`pl`) | Present at 100% in public API; wording not audited |

English is the shared base, not separate USA/UK translations. Current validation
covers every locale above. To refresh the machine-readable inventory and length
reports, run `scripts/check-watch-translations.sh --report-dir build/reports/translations`.

For **each** existing language, if reimporting or reconfiguring the watch component:

1. Check the real component's repository, branch, source language, mask and filter.
2. Confirm the file is detected as an existing translation, not a new blank language.
3. Match all 1,802 source entries (1,744 strings plus 58 plural resources). Weblate
   may display plural units/forms differently; compare keys and values, not just
   one dashboard percentage.
4. Inspect actual imported ordinary, formatted, plural, annotated, newline, ICU,
   warning and destructive-action strings. Keep approved wording and arguments.
5. Compare the first proposed export/commit against GitHub with editing and
   outbound synchronization still locked. Record detection, import and unchanged
   wording separately for every locale. No reset-to-English or machine translation.
6. If the serializer changes quoting, escaping, indentation, order or multiline
   layout substantially, stop and show the proposed diff. Do not activate a giant
   formatting-only rewrite or accept it as a translation update.

Weblate's Android format can change quoting and newline serialization. Existing
quoted XML and ordinal annotations make a no-op round-trip check important.
Local format probing is useful evidence, but **does not confirm Hosted Weblate
import** or the serializer version used by that service.

Before integration, an isolated Translate Toolkit 3.20.0 probe recognized the
original 1,768 units (1,710 strings and 58 plurals) in each of de/es/fr/it/pl. No-edit serialization was byte-identical for every file.
Assigning the existing Cancel value back to itself only removed that string's
unnecessary outer quotes (one changed line per locale). No source file was
changed. This suggests no initial bulk rewrite for that parser version, while
showing why actual exports and automatic fixups still need review. Repeat the
round-trip review for the current 1,802 entries before enabling Hosted writes.

## Component instructions to paste into Weblate

> GlanceMap runs on a very small watch. Preserve meaning first, then natural
> language, clarity, and compactness. When two translations are equally natural
> and accurate, prefer the shorter one. Avoid cryptic abbreviations.
>
> Never break placeholders: `%1$s`, `%1$d`, `%2$s`, `%1$.1f`, and `%%` must retain
> their indexes, types and precision. Reordering is allowed. Preserve annotations,
> XML markup, intentional newlines, spacing and units.
>
> Keep GlanceMap, OpenStreetMap, Mapsforge, BRouter, Refuges.info, GPX and GPS where
> they are product names or intentional technical terms. Follow the glossary in
> context. If the meaning is unclear, ask in comments rather than guessing.
>
> Spoken-distance values use bare ICU PluralFormat patterns, not full
> MessageFormat messages. Keep `#`, decimal distance meaning, and “for” versus “in”.
> Never change routing, rounding or navigation timing to solve wording.
>
> Legal, safety, licensing and attribution translations require maintainer review
> before merging. Existing approved translations must not be bulk replaced.

Enable Weblate's Android/Java printf, XML/markup, newline/whitespace and plural
checks. Keep length checks advisory. The format enables Java printf checking.
Do **not** apply `icu-message-format` blindly to the three bare PluralFormat
strings; it expects another grammar. Their authoritative parse and decimal checks
run in GlanceMap CI using ICU. Do not run automatic XML normalization/fixup add-ons
or automatically accept suggestions during initial import.

## Glossary import

After the project and all five languages are safely imported, open its Glossary.
For each corresponding target language, upload the UTF-8 CSV in
`docs/translation-glossary/<locale>.csv`, choosing the CSV format with header
fields. Map `source`, `target`, and `developer_comments` as documented by Weblate.
Review a preview before accepting. Do not overwrite existing glossary entries
without comparing them; preserve legitimate context variants.

Only approved languages have target terms. Use explanations/variants for context,
and mark product names and intentional abbreviations untranslatable. Never fill
new-language terms with guessed translations. See the official
[glossary import](https://docs.weblate.org/en/latest/user/glossary.html#glossary-import)
and [CSV format](https://docs.weblate.org/en/latest/formats/csv.html) instructions.

## Legal / Credits: separate, not enabled yet

Preserve all five `raw-*/legal_*.md` bundles. Do not point an active Weblate Markdown
component at them: its documented import limitation makes Weblate, rather than
the existing translated Markdown, authoritative. That conflicts with this
repository's preservation and GitHub-authority requirements.

Reserve a separate **Legal / Credits** component/category with suggestion/review
permissions only and maintainer acceptance. Before activating it, design and test
a lossless import into a stable keyed format (for example, reviewed PO/XLIFF
paragraph units), preserve all existing wording/URLs/quotes/markup, and verify an
unchanged Markdown export. That conversion is deliberately not performed here.
No raw-file mask or automatic content regeneration is enabled meanwhile.

Until then, the issue form and separately reviewed GitHub PRs support legal
corrections. The Markdown limitation is documented by
[Weblate's Markdown backend](https://docs.weblate.org/en/latest/formats/markdown.html).
UI resources presenting legal or safety text also need maintainer review; being
in the Wear OS component does not waive that requirement.

## Activation and maintenance

The intended path is:

```text
Community translator → Hosted Weblate → translation checks → GitHub translation PR
→ GlanceMap localization CI → maintainer review → merge into main
```

The `translation` label identifies wording/localization contributions. The issue
form has all current languages plus Other / new language; that fallback also
accepts newly added locales before its convenience dropdown is refreshed.

After the infrastructure and resources reach `main` and a successful run exists,
require the **Localization checks** status and maintainer approval in the existing
GitHub branch rule/ruleset. Preserve other required checks, disallow direct pushes
and force pushes, and give the Weblate App no `main` bypass. Do not automatically
merge Weblate PRs. This rule is not activated before its check exists: doing so
would block unrelated PRs while these files are still local.

Translators receive Weblate translation/suggestion permissions, not arbitrary
GitHub write access. The bot writes only its translation branch and opens a PR.
Incoming GitHub changes are authoritative; review concurrent direct XML changes
and pending Weblate edits to avoid conflicts. Do not resolve a conflict by bulk
regeneration or automatic replacement of approved wording.

The README and `TRANSLATING.md` link to the existing project. Add a status badge
only once the service supplies a stable badge/link.

The localization workflow runs for PRs to `main` and the current setup branch,
including fork PRs. It uses `pull_request`, read-only repository permissions and
no credentials from PR authors. There is no `pull_request_target` execution,
write-token job, automatic merge, or secret-bearing callback script.
It compares locale file paths against the exact PR/push base commit to reject
silent removal of existing UI or raw/legal translations, while allowing a new
UI language to use English legal fallback until its reviewed bundle is ready.

Whitespace checking compares the exact PR base SHA or push `before` SHA with the
checked-out `HEAD`. A new-branch push has no reliable before commit, so it checks
the entire committed tree against an empty tree. Manual runs check `HEAD^` against
`HEAD`; a root commit uses the empty-tree comparison. No branch-name guessing or
extra fetch is needed with the existing full-history checkout.

Localization CI runs Python/JVM resource checks and audit reports, without Gradle
or Android SDK setup. The existing Android CI owns Kotlin compilation, resource
compilation, JVM tests and lint on main PRs/pushes. Translation instrumentation
compilation runs in that same Android job; it does not execute device tests.
This avoids a second Android build while keeping the localization status present
on all applicable PRs, including documentation-only changes.

ICU4J 79.1 is a checksum-pinned **host validation tool**, covered by Unicode's
[license](https://www.unicode.org/license.txt). It is not packaged in the app or
added to Android runtime dependencies. No application behavior is changed by
this infrastructure.
