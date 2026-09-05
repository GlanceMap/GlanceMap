# Contributing

## Before You Start

1. Use JDK 17.
2. Install Android SDK components for `compileSdk = 36`.
3. Verify local build:

```bash
./gradlew :app:compileDebugKotlin :glancemapcompanionapp:compileDebugKotlin
```

## Project Conventions

- Keep transfer protocol constants in `:transfercontract` only.
- Keep watch background services under `app/.../core/service`.
- Keep feature UI code under `app/.../presentation/features/<feature>`.
- Prefer small, focused files over large multipurpose files.

## Local Online Map Configuration

The phone companion's Satellite map uses MapTiler and Tracestrack Topo uses
Tracestrack. Configure either provider locally through
`~/.gradle/gradle.properties` or its environment variable; never add values to
the repository's `gradle.properties`:

```properties
MAPTILER_API_KEY=<your MapTiler key>
TRACESTRACK_API_KEY=<your Tracestrack key>
```

Debug builds may omit this setting; Satellite remains visible but disabled.
Tracestrack Topo is likewise visible but disabled without its key. Release
artifact tasks currently treat both providers as optional, so they remain
disabled when no key is configured. These keys are embedded in Android tile
requests and should therefore be restricted with the providers' quotas and
application restrictions where available.

## Branches and PRs

1. Create a focused branch per change.
2. Keep PR scope small and cohesive.
3. Include a short architecture impact note in the PR description if package/file layout changes.

## Validation Checklist

Run before opening a PR:

```bash
./gradlew :app:compileDebugKotlin
./gradlew :glancemapcompanionapp:compileDebugKotlin
```

Run the full local CI gate before merging release or public-repo changes:

```bash
./gradlew ktlintCheck detekt :app:compileDebugKotlin :glancemapcompanionapp:compileDebugKotlin :app:testDebugUnitTest :glancemapcompanionapp:testDebugUnitTest :app:lintDebug :glancemapcompanionapp:lintDebug
```

If your change touches transfer behavior, also verify:

- watch receives transfer requests,
- status/ack messages still flow phone <-> watch,
- LAN HTTP transfer path still works.

## Location Changes

If your change touches `app/.../core/service/location`, also:

1. Follow `docs/location/CONTRIBUTING.md` for validation and PR evidence.
2. Update `docs/location/THRESHOLDS.md` when changing location constants.
3. Update `docs/location/ARCHITECTURE.md` and/or `docs/architecture/wear-location-service.md` when package ownership or flow changes.

## Compass Changes

If your change touches compass behavior, also:

1. Follow `docs/compass/CONTRIBUTING.md` for validation and PR evidence.
2. Update `docs/compass/THRESHOLDS.md` when changing compass constants.
3. Update `docs/compass/ARCHITECTURE.md` and/or `docs/architecture/wear-navigation.md` when ownership or flow changes.

## Documentation

When structure or behavior changes:

1. Update relevant file-level comments (if needed).
2. Update architecture docs in `docs/architecture/`.
3. Update root `README.md` if setup or module responsibilities changed.
4. If adding or upgrading third-party dependencies, update `licenses/THIRD_PARTY_NOTICES.md` and `licenses/RUNTIME_DEPENDENCIES_RESOLVED.txt`.

Before making the repository public, follow `docs/PUBLIC_REPO_CHECKLIST.md`.
