# Same-host RDID locking regression

Compiles the four current production readers (TCL config, IMA config, app helper,
IMA shared core), with small Android boundary stubs and **real JVM file locks**.
It does not copy/reimplement the production locking algorithm or play ads.

Run after the project's Gradle dependencies and Android 36 SDK are available:

```sh
export JAVA_HOME=/path/to/jdk-21
python3 /absolute/path/to/ad-app/ad-sdk-tcl/src/test/identity-lock/run.py
```

`JAVA_HOME`, `ANDROID_HOME`, and `GRADLE_USER_HOME` are supported. The default
output is the repository's `build/identity-lock-regression`; `--work-dir` selects
another generated-output directory. A failed correctness assertion exits nonzero.

Coverage:

- Ten same/cross-reader pairs share one canonical lock file, including aliased
  path spellings. A second thread must wait; both IDs match and only one is saved.
- An existing ID is preserved, without generating or writing another value.
- An inaccessible lock fails without reading/writing preferences or returning
  an unlocked generated ID.
- Android-style failed commits update the memory cache: synchronous cleanup
  clears that unconfirmed value, and a retry must persist a new value.
- Bytecode of all four production readers must not reference API-26 Path,
  StandardOpenOption, or FileChannel.open(Path) APIs.

The harness always releases the first reader after a bounded observation;
correct serialization is not forced into a two-thread barrier inside a lock.
This suite verifies the participating readers in one process, not Android
SharedPreferences cache coherence between processes or an API-24 device run.

The fixtures live outside Gradle's default `src/test/java`, so their Android
stubs cannot shadow framework classes in the normal unit-test suite.
