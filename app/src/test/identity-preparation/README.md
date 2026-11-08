# Identity preparation error regression

After resolving the project's Gradle dependencies, run with a JDK:

```sh
export JAVA_HOME=/path/to/jdk-21
python3 /absolute/path/to/ad-app/app/src/test/identity-preparation/run_authorize_failure.py
python3 /absolute/path/to/ad-app/app/src/test/identity-preparation/run_prepare_paths.py
```

Generated stubs/classes are written to the repository's
`build/identity-preparation-regression`. These fixtures are outside Gradle's
default `src/test/java` and do not shadow Android classes in normal unit tests.

- The authorization probe compiles the complete current production client and
  injects an identity-storage failure. It requires an error callback, no escaped
  exception and no network request.
- The preparation probe extracts current TCL/IMA preparation and cleanup method
  bodies verbatim, with narrow Android/player/network collaborators. It covers
  failure cleanup, normal success, synchronous replacement, and preservation of
  the newer owner. Two reconstructed pre-repair negative controls plus seven
  repaired-path scenarios are run; these are not historical APK executions.

The probes do not play ads or make production requests. They complement the
normal Gradle suite and the real-file-lock matrix in
`ad-sdk-tcl/src/test/identity-lock`, not a new device/CMP/measurement matrix.
