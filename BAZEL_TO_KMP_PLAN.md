# Bazel Java to Kotlin Multiplatform (KMP) Migration Plan

This document outlines a comprehensive, iterative strategy for migrating a massive, auto-converted Java codebase (specifically the Bazel `options` package and related core libraries) to Kotlin Multiplatform (KMP), with a primary goal of compiling to Kotlin/Native.

This plan incorporates hard-learned lessons from initial migration attempts, detailing blockers encountered and strategic workarounds required for a successful conversion.

---

## 1. Lessons Learned & Blockers Encountered

During the initial phase of fixing the IntelliJ Java-to-Kotlin auto-conversion, several systemic issues were identified:

*   **Blocker 1: Kapt and Annotation Defaults.**
    *   *Issue:* The `@Option` annotation uses a default property `Class<? extends Converter> converter() default Converter.class`. When converted to Kotlin, Kotlin's `KClass` interoperability with Java annotations causes severe `kapt` stub generation failures (`incompatible types: NonExistentClass cannot be converted to Annotation`).
    *   *Workaround:* Annotations used by annotation processors should either be temporarily reverted to pure `.java` files, or the project must migrate to Kotlin Symbol Processing (KSP) instead of `kapt`.
*   **Blocker 2: Strict Nullability vs. Java's "Loose" Generics.**
    *   *Issue:* Java allows a generic `T` to encompass `null`. Kotlin strictly separates `T` and `T?`. In complex inheritance hierarchies like `Converter<T>`, `Contextless<T>`, and `EnumConverter<T : Enum<T>>`, the auto-converter fails to accurately predict whether bounds should be nullable. This leads to hundreds of cascading `overrides nothing` and `type mismatch` errors.
    *   *Solution:* A strict, manually-verified nullability contract must be established for core interfaces *before* fixing implementations.
*   **Blocker 3: Java API Residue.**
    *   *Issue:* The auto-converter translates `list.size()` to `list.size()` instead of `list.size`, `string.length()` to `string.length()`, and leaves behind `java.util.Collections`, `java.util.stream.Stream`, and `java.util.regex.Pattern`.
    *   *Solution:* Targeted regex passes followed by manual compilation cycles are required to strip Java idioms and replace them with Kotlin Standard Library equivalents.
*   **Blocker 4: Runtime Reflection.**
    *   *Issue:* The `OptionsParser` heavily relies on `java.lang.reflect` to inspect `@Option` annotations, read fields, and instantiate classes.
    *   *KMP Blocker:* `java.lang.reflect` does not exist in Kotlin/Native.
    *   *Solution:* We must ultimately eliminate runtime reflection, replacing it with a compile-time KSP processor that generates option registration code.

---

## 2. Phased Migration Strategy

To prevent compilation errors from spiraling out of control, a "Big Bang" migration to KMP is impossible. The migration must follow a strict, iterative phased approach.

### Phase 1: JVM Stabilization (Kotlin-JVM)
*Goal: Get the Kotlin code compiling and passing tests on the JVM before attempting Native compilation.*

1.  **Guava & Java Collections Eradication:**
    *   Replace `ImmutableList`, `ImmutableSet`, `ImmutableMap` with standard Kotlin `List`, `Set`, `Map`.
    *   Replace `java.util.Optional` with Kotlin nullable types (`T?`).
    *   Replace `com.google.common.base.Preconditions` with Kotlin `require()` and `check()`.
    *   Replace `com.google.common.base.Splitter` and `Joiner` with Kotlin `.split()` and `.joinToString()`.
2.  **Core Interface Type Signatures:**
    *   Manually repair the generic signatures in the root interfaces.
    *   Example: Force `Converter<T>` to use explicit nulls: `fun convert(input: String?): T` and ensure implementations don't accidentally define `T?` return types.
3.  **Annotation Reversion:**
    *   Keep `@Option` and `OptionDocumentationCategory` as `.java` files to ensure Bazel's build constraints and existing Java processors can still read them during the transition.
4.  **Syntax Cleanup:**
    *   Run sweeping automated replacements for `.size() -> .size`, `.length() -> .length`, `entrySet() -> entries`, `getKey() -> key`, `getValue() -> value`.

### Phase 2: KMP Preparation (Isolating JVM APIs)
*Goal: Remove dependencies on `java.*` packages that will fail in Kotlin/Native.*

1.  **Regex to Kotlin Text:**
    *   Migrate usages of `java.util.regex.Pattern` and `Matcher` to `kotlin.text.Regex`.
2.  **File I/O Abstraction:**
    *   Replace `java.nio.file.Path` and `FileSystem` with a multiplatform abstraction like `okio.Path` or `kotlinx.io`.
3.  **Concurrency Primitives:**
    *   Replace `ConcurrentHashMap` used in caches with KMP-compatible concurrent maps (e.g., from `stately-concurrency` or atomic references).
    *   Replace `java.util.concurrent` synchronizers with `kotlinx.coroutines.sync.Mutex`.
4.  **Time APIs:**
    *   Replace `java.time.Duration` with `kotlin.time.Duration`.
5.  **Logging:**
    *   Replace `java.util.logging.Level` with a multiplatform logging interface (e.g., Kermit or Napier).

### Phase 3: The Reflection Removal (KSP)
*Goal: Remove `java.lang.reflect` which blocks Kotlin/Native.*

1.  **Introduce Kotlin Symbol Processing (KSP):**
    *   Write a KSP processor that reads the `@Option` annotations at compile time.
2.  **Generate Parser Boilerplate:**
    *   Instead of `IsolatedOptionsData` using `Class.getMethods()` to build the option dictionary at runtime, the KSP processor will generate a static Kotlin object mapping command-line arguments to field accessors and converters.
3.  **Remove `OptionsBase` Inheritance Restrictions:**
    *   Alter `OptionsBase` to utilize the generated parsers instead of reflective field injection.

### Phase 4: Kotlin/Native Migration
*Goal: Compile as a `kt_native_library`.*

1.  **Update Bazel Targets:**
    *   Switch `kt_jvm_library` rules to `kt_native_library` or `kt_multiplatform` depending on the surrounding ecosystem.
2.  **Native Memory Management:**
    *   Audit global state and companion objects for Kotlin/Native memory model compatibility (ensure immutable state where necessary, though Kotlin 1.9+ new memory model makes this easier).
3.  **Cross-Compilation Testing:**
    *   Migrate tests to `kotlin.test`.
    *   Execute the test suite against Linux/macOS native targets to verify option parsing logic mirrors the JVM execution accurately.

---

## 3. Recommended Immediate Next Steps

If resuming work on this repository today, **do not attempt to fix all `Converter` implementations at once.**

1.  **Revert** the highly broken auto-converted `Converter.kt`, `Converters.kt`, `BoolOrEnumConverter.kt`, and `EnumConverter.kt` back to `.java` temporarily.
2.  **Verify** that the rest of the parsing logic (`OptionsParserImpl.kt`, `IsolatedOptionsData.kt`) compiles cleanly when referencing the Java Converter interfaces. This establishes a functioning baseline.
3.  **Migrate** the Converters one at a time, strictly enforcing the `T` vs `T?` nullability contracts manually rather than relying on regex replacements, ensuring `kt_jvm_library` compiles successfully after *each* file.
4.  Once `options` compiles cleanly as a mixed Kotlin/Java JVM library, begin **Phase 2** (removing `java.util.regex`, `java.nio.file`, etc.).