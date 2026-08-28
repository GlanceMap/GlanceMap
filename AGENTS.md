# Repository instructions

## Secret-handling rules

- Never commit API tokens, passwords, private keys, signing credentials, or production configuration values.
- Never read, print, log, document, or expose secret values.
- Never include real secrets in tests, examples, screenshots, comments, prompts, or generated files.
- Use placeholders in committed configuration examples.
- Before committing changes, inspect all staged files for credentials and tokens.
- Production credentials must be supplied through local Gradle properties or CI secrets.

## Understand before editing

- Read relevant code and tests; trace the real execution/data flow and important callers.
- Prefer shared root-cause fixes over individual symptom patches.
- Smallest correct diff wins only after understanding the affected flow.
- Reuse repository patterns; do not add speculative abstractions, dependencies, infrastructure, or boilerplate.

## Behavior preservation

Do not change validated product behavior merely to satisfy formatting, static analysis, complexity, or code-size metrics. Preserve:

- recording/GPX, GPS/location continuity, sparse cadence, pause/resume, activity timing, distance, and sensor-source separation;
- track smoothing and turn/switchback/detour safety, elevation/barometer fusion, map/navigation safety, and hardware calibration, heuristics, and thresholds.

When a task intentionally changes behavior, add or update focused regression coverage.

For lint fixes on validated behavior, prefer this order:

1. formatting-only changes;
2. extraction of pure/mechanical helpers;
3. small immutable input/context data classes; then
4. narrow, documented function/class-level suppressions when further refactoring would make validated orchestration riskier or less clear.

Never change algorithms, constants, thresholds, state transitions, or safety checks merely to satisfy lint. Do not globally weaken detekt or use broad baselines or suppressions.

## Quality gate

For code changes intended to be committed or pushed, run `scripts/quality-gate.sh`. It is the canonical local repository gate and mirrors the required Gradle checks plus `git diff --check`.

Run focused tests in addition when changing behavior. Do not report work as complete, ready to merge, or CI-clean unless the applicable checks passed. If the full gate cannot run, state what was not run and why.

## Hardware and real-world validation

CI does not substitute for real-device or real-world validation when behavior depends on GPS, sensors, barometer, hardware quirks, battery/runtime behavior, or physical-world calibration. Report required but unperformed validation explicitly.

## Branch and PR hygiene

- Keep one coherent task per branch and PR, starting from an up-to-date intended base branch.
- Before staging or committing, inspect `git status` and the staged diff.
- Do not silently absorb unrelated files, version bumps, formatting, diagnostics, or work from another task; stop and report unexpected changes.
- Never discard, rewrite, apply, or drop unrelated stashes or backups. Preserve a recoverable reference before reworking overlapping changes.
- Use `--force-with-lease` instead of plain `--force` when needed; never rewrite a recovery/backup branch for integration.

## Completion report

Before reporting completion, state:

- what behavior/files changed and what was intentionally left untouched;
- focused tests run, full quality-gate result, and `git diff --check` result;
- any lint suppressions added and why;
- real-device/hardware validation performed, still required, or unavailable; and
- unrelated work discovered and left untouched.

Do not hide remaining failures or silently change scope merely to make CI pass.
