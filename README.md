# Do In Place

## Release Ledger

Keep this section updated before building release artifacts so the same version is not reused.

| Status | versionCode | versionName | Notes |
| --- | ---: | --- | --- |
| Current working release | 30 | 2.4.7 | Canonical shopping item extraction + receipt suggestion cleanup |
| Previously used | 28 | 2.4.5 | Already existed before this release bump |

## Release Rule

- Before `assembleDebug` or `bundleRelease`, check `app/build.gradle.kts` and this table.
- If the current version is already shipped, increment both values first.
- Record the new pair here in the same commit as the version bump.
