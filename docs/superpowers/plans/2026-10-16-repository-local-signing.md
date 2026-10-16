# Repository-Local Signing Implementation Plan

**Goal:** Include the three externally referenced signing configurations and their original keystores in AD_APP.

**Architecture:** Preserve all signing identities and flavor mappings. Load each properties file from `app/signing_files` and resolve its keystore relative to that properties file. The user explicitly approved committing and publishing these credentials to the currently public repository on 2026-10-16.

**Tech Stack:** Gradle Groovy, Java properties, Java KeyStore, JUnit 4.

## Scope

- `localVideoPlayerRelease`: Local Video Player upload signing used by `google_ad_tv_desktop_v260904_1`.
- `tclDemoRelease`: Existing TCL release signing.
- `tclDemoNoneuRelease`: Existing non-EU TCL release signing.
- Leave unrelated SDK/library dependencies, flavors, and signing identities unchanged.

## Steps

- [x] Add `RepositorySigningContractTest.java` covering local properties paths, complete signing credentials, loadable private keys, and the customer's debug/release signing mapping.
- [x] Compile the test with `javac --release 11` and run `org.junit.runner.JUnitCore`; confirm it fails because the repository-local signing inputs are missing.
- [x] Copy the three original keystores without modifying their contents. Copy their properties using only a repository-local `storeFile` filename.
- [x] Update the three property loaders and keystore path resolvers in `app/build.gradle` without changing passwords, aliases, or store types.
- [x] Update the channel's existing design document to describe repository-local signing and the approved credential publication.
- [x] Run the contract test again, compare copied keystores and credential values with their originals, and validate Gradle signing for the affected configurations.
- [x] Review the staged diff and confirm all six signing files are tracked.

## Verification Results

- The initial JUnit run failed on the missing repository-local loader and properties file; the customer signing mapping test already passed.
- The final JUnit run passed all three tests, including opening each private key with the preserved credentials.
- All three copied keystores are byte-identical to their originals. Each properties file changes only `storeFile`.
- `./gradlew :app:signingReport --console=plain` passed and resolved the affected debug/release variants to keystores under `AD_APP/app/signing_files`.
- `git diff --cached --check` passed.

## Delivery

Commit the focused changes, push `main`, and verify the remote commit hash.
