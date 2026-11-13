# yourtube: droid app

An original app (no upstream), maintained as a droid app and shipped as signed GitHub releases. The
full conventions and workflows (change, build, release) live in the droid skill; load it with `/droid`
before any branch, signing or release work. The ground rules below hold in every session, skill loaded
or not.

## This repo

- `origin` is Staninna/yourtube. There is no `upstream`.
- `my-main` holds all work and is the GitHub default branch.
- Release build: `:app:assembleRelease`, JDK 17. `.github/workflows/release.yml` is the only release
  path.

## Ground rules

- **Commit to `my-main`**, directly or through a short-lived branch merged into it. `my-main` is
  published, so its history only grows: merge, never rebase.
- **A push to `my-main` is a release.** CI tags it `v<versionName>-<n>` and publishes signed APKs to
  users. Push only when the user asks and the work is verified. Pushes that only touch the
  `paths-ignore` list in `release.yml` (docs, `*.md`) or carry `[skip ci]` don't release.
- **Verify before committing:** build the debug variant and run the unit tests. Before pushing, also
  build the minified release task above: R8 problems only show up there.
- **Tags:** pushed tags stay where they are; a failed release is fixed by pushing a fix to `my-main`.
- **Signing keys are hands-off.** The key lives outside the repo; only the droid scripts
  (`signing-key.sh`, `build-signed.sh`) touch it. Never read, print or create keystores (`*.jks`,
  `*.keystore`, `keystore.properties`) or anything in the release-keys folder.
- **Commit identity:** the GitHub noreply address in the repo's local git config. Before committing,
  confirm `git config --local user.email` ends in `@users.noreply.github.com`.
- **Small, focused changes** in the app's own style and architecture.
