// Copyright 2014 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.skyframe

import com.google.devtools.build.lib.analysis.BlazeDirectories

/**
 * A Skyframe function to look up and load a single .bzl (or .scl) module.
 * 
 * 
 * Note: Historically, all modules had the .bzl suffix, but this is no longer true now that Bazel
 * supports the .scl dialect. In identifiers, code comments, and documentation, you should generally
 * assume any "bzl" term could mean a .scl file as well.
 * 
 * 
 * Given a [Label] referencing a .bzl file, attempts to locate the file and load it. The
 * Label must be absolute, and must not reference the special `external` package. If loading
 * is successful, returns a [BzlLoadValue] that encapsulates the loaded [Module] and its
 * transitive digest information. If loading is unsuccessful, throws a [ ] that encapsulates the cause of the failure.
 * 
 * 
 * This Skyframe function supports a special bzl "inlining" mode in which all (indirectly)
 * recursive calls to `BzlLoadFunction` are made in the same thread rather than through
 * Skyframe. This inlining mode's entry point is [.computeInline]; see that method for more
 * details. Note that it may only be called on an instance of this Skyfunction created by [ ][.createForInlining]. Bzl inlining is not to be confused with the separate inlining of `BzlCompileFunction`
 */
class BzlLoadFunction private constructor(
    ruleClassProvider: RuleClassProvider,
    directories: BlazeDirectories,
    getter: ValueGetter,
    inlineCacheManager: InlineCacheManager?
) : SkyFunction {
    // Used for: 1) obtaining info needed to construct the BzlInitThreadContext object and to locate
    // the builtins bzl files; and 2) providing a BazelStarlarkEnvironment to other Skyfunctions
    // (StarlarkBuiltinsFunction, BzlCompileFunction) when they are inlined and called via a static
    // computeInline() entry point.
    private val ruleClassProvider: RuleClassProvider

    // Used for determining paths to builtins bzls that live in the workspace.
    private val directories: BlazeDirectories

    // Handles retrieving BzlCompileValues, either by calling Skyframe or by inlining
    // BzlCompileFunction; the latter is not to be confused with inlining of BzlLoadFunction. See
    // comment in create() for rationale.
    private val getter: ValueGetter

    // Handles inlining of BzlLoadFunction and StarlarkBuiltinsFunction calls.
    val inlineCacheManager: InlineCacheManager?

    init {
        this.ruleClassProvider = ruleClassProvider
        this.directories = directories
        this.getter = getter
        this.inlineCacheManager = inlineCacheManager
    }

    @Throws(SkyFunctionException::class, java.lang.InterruptedException::class)
    override fun compute(skyKey: SkyKey, env: SkyFunction.Environment): SkyValue? {
        val key: BzlLoadValue.Key = skyKey.argument() as BzlLoadValue.Key
        try {
            return computeInternal(key, env,  /* inliningState= */null)
        } catch (e: BzlLoadFailedException) {
            throw BzlLoadFunctionException(e)
        }
    }

    /**
     * Entry point for computing "inline", without any direct or indirect Skyframe calls back into
     * [BzlLoadFunction]. (Other Skyframe calls are permitted.)
     * 
     * 
     * **USAGE NOTES:**
     * 
     * 
     *  * This method is intended to be called from [PackageFunction] and [       ] and probably shouldn't be used anywhere else. If you think you
     * need inline Starlark computation, consult with the Core subteam and check out
     * cl/305127325 for an example of correcting a misuse.
     *  * If this method is used with --keep_going and if Skyframe evaluation will never be
     * interrupted, then this function ensures that the evaluation graph and any error reported
     * are deterministic.
     * 
     * 
     * 
     * Under bzl inlining, there is some calling context that wants to obtain a set of [ ]s without Skyframe evaluation. For example, a calling context can be a BUILD file
     * trying to resolve its top-level `load` statements. Although this work proceeds in a
     * single thread, multiple calling contexts may evaluate .bzls in parallel. To avoid redundant
     * work, they share a single (global to this Skyfunction instance) cache in lieu of the regular
     * Skyframe cache. Unlike the regular Skyframe cache, this cache stores only successes.
     * 
     * 
     * If two calling contexts race to compute the same .bzl, each one will see a different copy of
     * it, and only one will end up in the shared cache. This presents a hazard: Suppose A and B both
     * need foo.bzl, and A needs it twice due to a diamond dependency. If A and B race to compute
     * foo.bzl, but B's computation populates the cache, then when A comes back to resolve it the
     * second time it will observe a different `BzlLoadValue`. This leads to incorrect Starlark
     * evaluation since Starlark values may rely on Java object identity (see b/138598337). Even if we
     * weren't concerned about racing, A may also reevaluate previously computed items due to cache
     * evictions.
     * 
     * 
     * To solve this, we keep a second cache, [InliningState.successfulLoads], that is local
     * to the current calling context, and which never evicts entries. Like the global cache discussed
     * above, this cache stores only successes. This cache is always checked in preference to the
     * shared one; it may deviate from the shared one in some of its entries, but the calling context
     * won't know the difference. (Since bzl inlining is only used for the loading phase, we don't
     * need to worry about Starlark values from different packages interacting.) The cache is stored
     * as part of the `inliningState` passed in by the caller; the caller can obtain this object
     * using [InliningState.create].
     * 
     * 
     * As an aside, note that we can't avoid having [InliningState.successfulLoads] by simply
     * naively blocking evaluation of .bzls on retrievals from the shared cache. This is because two
     * contexts could deadlock while trying to evaluate an illegal `load()` cycle from opposite
     * ends. It would be possible to construct a waits-for graph and perform cycle detection, or to
     * monitor slow threads and do detection lazily, but these do not address the cache eviction
     * issue. Alternatively, we could make Starlark tolerant of reloading, but that would be
     * tantamount to implementing full Starlark serialization.
     * 
     * 
     * Since our local [InliningState.successfulLoads] stores only successes, a separate
     * concern is that we don't want to unsuccessfully visit the same .bzl more than once in the same
     * context. (A visitation is unsuccessful if it fails due to an error or if it cannot complete
     * because of a missing Skyframe dep.) To address this concern we maintain a separate [ ][InliningState.unsuccessfulLoads] set, and use this set to return null instead of duplicating an
     * unsuccessful visitation.
     * 
     * @return the requested `BzlLoadValue`, or null if there was a missing Skyframe dep, an
     * unspecified exception in a Skyframe dep request, or if this was a duplicate unsuccessful
     * visitation
     */
    // TODO(brandjon): Pick one of the nouns "load" and "bzl" and use that term consistently.
    @Throws(BzlLoadFailedException::class, java.lang.InterruptedException::class)
    fun computeInline(key: BzlLoadValue.Key, inliningState: InliningState): BzlLoadValue? {
        com.google.common.base.Preconditions.checkNotNull<InlineCacheManager?>(inlineCacheManager)
        val cachedData: CachedBzlLoadData? = computeInlineCachedData(key, inliningState)
        return if (cachedData != null) cachedData.getValue() else null
    }

    /**
     * Retrieves or creates the requested [CachedBzlLoadData] object for the given bzl, entering
     * it into the local and shared caches. This is the entry point for recursive calls to the inline
     * code path.
     * 
     * @return null if there was a missing Skyframe dep, an unspecified exception in a Skyframe dep
     * request, or if this was a duplicate unsuccessful visitation
     */
    @Throws(BzlLoadFailedException::class, java.lang.InterruptedException::class)
    private fun computeInlineCachedData(
        key: BzlLoadValue.Key, inliningState: InliningState
    ): CachedBzlLoadData? {
        // Try the caches of successful loads. We must try the thread-local cache before the shared, for
        // consistency purposes (see the javadoc of #computeInline).
        var cachedData: CachedBzlLoadData? = inliningState.successfulLoads.get(key)
        if (cachedData == null) {
            cachedData = inlineCacheManager.bzlLoadCache.getIfPresent(key)
            if (cachedData != null) {
                // Found a cache hit from another thread's computation. Register the cache hit's recorded
                // deps as if we had requested them directly in the unwrapped environment. We do this for
                // the unwrapped environment, not the recording environment, because there's no need to
                // embed one CachedBzlLoadData's metadata inside another; the dependency relationship will
                // still be accurately reflected in the cache by the call to addTransitiveDeps() via
                // childCachedDataHandler at the bottom of this function.
                //
                // Also incorporate into successfulLoads any transitive cache hits that it does not already
                // contains.
                cachedData.traverse(
                    { keys: Iterable<SkyKey?>? -> inliningState.recordingEnv.getDelegate().registerDependencies(keys) },
                    inliningState.successfulLoads
                )
            }
        }

        // See if we've already unsuccessfully visited the bzl. "Unsuccessfully" includes getting null
        // for a missing Skyframe dep; the top-level caller will use a fresh InliningState when it does
        // its Skyframe restart.
        if (inliningState.unsuccessfulLoads.contains(key)) {
            return null
        }

        // If we're here, the bzl must have never been visited before in this calling context. Compute
        // it ourselves, updating the other data structures as appropriate.
        if (cachedData == null) {
            try {
                cachedData = computeInlineForCacheMiss(key, inliningState)
            } finally {
                if (cachedData != null) {
                    inliningState.successfulLoads.put(key, cachedData)
                    inlineCacheManager.bzlLoadCache.put(key, cachedData)
                } else {
                    inliningState.unsuccessfulLoads.add(key)
                    // Either propagate an exception or fall through for null return.
                }
            }
        }

        // On success (from cache hit or from scratch), notify the parent CachedBzlLoadData of its new
        // child.
        if (cachedData != null) {
            inliningState.childCachedDataHandler.accept(cachedData)
        }

        return cachedData
    }

    @Throws(BzlLoadFailedException::class, java.lang.InterruptedException::class)
    private fun computeInlineForCacheMiss(
        key: BzlLoadValue.Key, inliningState: InliningState
    ): CachedBzlLoadData? {
        // We use an instrumented Skyframe env to capture Skyframe deps in the CachedBzlLoadData (see
        // InliningState#recordingEnv). This generally includes transitive Skyframe deps, but
        // specifically excludes deps underneath recursively loaded .bzls. In this way, the
        // CachedBzlLoadData objects form a DAG that mirrors the bzl load graph: Each node still reaches
        // *all* the transitive skyframe deps needed for its computation, but the bzl-level granularity
        // allows for sharing of cached results for portions of the bzl load graph.
        //
        // Here we are at the boundary between one CachedBzlLoadData and the next. createChildState()
        // unwraps the old recording env and starts a new one for a new node.

        val childState = inliningState.createChildState(inlineCacheManager)
        childState.beginLoad(key) // track for cyclic load() detection
        var value: BzlLoadValue?
        try {
            value = computeInternal(key, childState.recordingEnv, childState)
        } finally {
            childState.finishLoad(key)
        }
        if (value == null) {
            return null
        }

        return childState.buildCachedData(key, value)
    }

    /** Re-initializes the bzl inlining cache, if this instance uses one. No-op otherwise.  */
    fun resetInliningCache() {
        inlineCacheManager.reset( /* resetBuiltins= */false)
    }

    /** Re-initializes the bzl inlining cache, if this instance uses one. No-op otherwise.  */
    @com.google.common.annotations.VisibleForTesting
    fun resetInliningCacheAndBuiltinsForTesting() {
        inlineCacheManager.reset( /* resetBuiltins= */true)
    }

    /**
     * An opaque object that holds state for the bzl inlining computation initiated by [ ][.computeInline].
     * 
     * 
     * An original caller of `computeInline` (e.g., [PackageFunction]) should obtain
     * one of these objects using [InliningState.create]. When the same caller makes several
     * calls to `computeInline` (e.g., for multiple top-level loads in the same BUILD file), the
     * same object must be passed to each call.
     * 
     * 
     * When a Skyfunction that is called by `BzlLoadFunction`'s inlining code path in turn
     * calls back into `computeInline`, it should forward along the same `InliningState`
     * that it received. In particular, [StarlarkBuiltinsFunction] forwards the inlining state
     * to ensure that 1) the .bzls that get loaded from the `@_builtins` pseudo-repository are
     * properly recorded as dependencies of all .bzl files that use builtins injection, and 2) the
     * builtins .bzls are not reevaluated.
     */
    // TODO(brandjon): Consider making this even more opaque and encapsulating more of the details of
    // inlining. E.g., merge beginLoad/finishLoad with child state tracking, and encapsulate
    // management of [un]successfulLoads.
    internal class InliningState private constructor(
        recordingEnv: RecordingSkyFunctionEnvironment,
        cachedDataBuilder: CachedBzlLoadData.Builder,
        loadStack: LinkedHashSet<BzlLoadValue.Key?>,
        successfulLoads: MutableMap<BzlLoadValue.Key?, CachedBzlLoadData?>,
        unsuccessfulLoads: HashSet<BzlLoadValue.Key?>,
        childCachedDataHandler: java.util.function.Consumer<CachedBzlLoadData?>
    ) {
        /**
         * The Skyframe environment, instrumented to record dependencies inside CachedBzlLoadData
         * objects. A new CachedBzlLoadData, and therefore a new recording environment, is started in
         * each call to computeInlineForCacheMiss(). The initial InliningState's recording environment
         * doesn't instrument anything since it represents the piece of the work that will not be saved
         * in any CachedBzlLoadData.
         */
        private val recordingEnv: RecordingSkyFunctionEnvironment

        /**
         * The builder of the CachedBzlLoadData node that we are currently working on, if any. Null iff
         * we're the initial InliningState, where recordingEnv doesn't instrument anything.
         */
        private val cachedDataBuilder: CachedBzlLoadData.Builder

        /**
         * The set of bzls we're currently in the process of loading but haven't fully visited yet. This
         * is used for cycle detection since we don't have the benefit of Skyframe's internal cycle
         * detection. The set must use insertion order for correct error reporting.
         * 
         * 
         * This is disjoint with [.successfulLoads] and [.unsuccessfulLoads].
         * 
         * 
         * This is local to current calling context. See [.computeInline].
         */
        // Keyed on the SkyKey, not the label, since label could theoretically be ambiguous, even though
        // in practice keys from BUILD / MODULE / builtins don't call each other.
        private val loadStack: LinkedHashSet<BzlLoadValue.Key?>

        /**
         * Cache of bzls that have been fully visited and successfully loaded to a value.
         * 
         * 
         * This and [.unsuccessfulLoads] partition the set of fully visited bzls.
         * 
         * 
         * This is local to current calling context. See [.computeInline].
         */
        private val successfulLoads: MutableMap<BzlLoadValue.Key?, CachedBzlLoadData?>

        /**
         * Set of bzls that have been fully visited, but were not successfully loaded to a value.
         * 
         * 
         * This and [.successfulLoads] partition the set of fully visited bzls, and is disjoint
         * with [.loadStack].
         * 
         * 
         * This is local to current calling context. See [.computeInline].
         */
        private val unsuccessfulLoads: HashSet<BzlLoadValue.Key?>

        /** Called when a transitive `CachedBzlLoadData` is produced.  */
        private val childCachedDataHandler: java.util.function.Consumer<CachedBzlLoadData?>

        init {
            this.recordingEnv = recordingEnv
            this.cachedDataBuilder = cachedDataBuilder
            this.loadStack = loadStack
            this.successfulLoads = successfulLoads
            this.unsuccessfulLoads = unsuccessfulLoads
            this.childCachedDataHandler = childCachedDataHandler
        }

        /**
         * Creates another InliningState from this one, but with the recording Skyframe environment set
         * up to log dependency metadata into a CachedBzlLoadData node that is a child of this
         * InliningState's node.
         */
        private fun createChildState(inlineCacheManager: InlineCacheManager): InliningState {
            val newBuilder: CachedBzlLoadData.Builder = inlineCacheManager.cachedDataBuilder()
            val newRecordingEnv: RecordingSkyFunctionEnvironment =
                RecordingSkyFunctionEnvironment(
                    recordingEnv.getDelegate(),
                    newBuilder::addDep,
                    newBuilder::addDeps,
                    newBuilder::noteException
                )
            return InliningState(
                newRecordingEnv,
                newBuilder,
                loadStack,
                successfulLoads,
                unsuccessfulLoads,
                newBuilder::addTransitiveDeps
            )
        }

        /**
         * Finishes construction of the current CachedBzlLoadData node. This InliningState object should
         * not be used after calling this method.
         */
        private fun buildCachedData(key: BzlLoadValue.Key?, value: BzlLoadValue?): CachedBzlLoadData {
            cachedDataBuilder.setValue(value)
            cachedDataBuilder.setKey(key)
            return cachedDataBuilder.build()
        }

        /** Records entry to a `load()`, throwing an exception if a cycle is detected.  */
        @Throws(BzlLoadFailedException::class)
        private fun beginLoad(key: BzlLoadValue.Key?) {
            if (!loadStack.add(key)) {
                val cycle: com.google.common.collect.ImmutableList<BzlLoadValue.Key?> =
                    CycleUtils.splitIntoPathAndChain(
                        com.google.common.base.Predicates.equalTo<T?>(key),
                        loadStack
                    ).second
                throw BzlLoadFailedException(
                    "Starlark load cycle: " + com.google.common.collect.Lists.transform<BzlLoadValue.Key?, Any?>(
                        cycle,
                        BzlLoadValue.Key::getLabel
                    ),
                    Code.CYCLE
                )
            }
        }

        /** Records exit from a `load()`.  */
        private fun finishLoad(key: BzlLoadValue.Key?) {
            com.google.common.base.Preconditions.checkState(loadStack.remove(key), key)
        }

        val environment: SkyFunction.Environment
            /** Retrieves the Skyframe environment to use to do work under this InliningState.  */
            get() = recordingEnv

        companion object {
            /**
             * Creates an initial `InliningState` with no information about previously loaded files
             * (except the shared cache stored in [BzlLoadFunction]).
             */
            fun create(env: SkyFunction.Environment?): InliningState {
                return InliningState(
                    RecordingSkyFunctionEnvironment(
                        env,
                        java.util.function.Consumer { x: SkyKey? -> },
                        java.util.function.Consumer { x: Iterable<SkyKey?>? -> },
                        java.util.function.Consumer { x: java.lang.Exception? -> }),  /* cachedDataBuilder= */
                    null,  /* loadStack= */
                    LinkedHashSet<BzlLoadValue.Key?>(),  /* successfulLoads= */
                    HashMap<BzlLoadValue.Key?, CachedBzlLoadData?>(),  /* unsuccessfulLoads= */
                    HashSet<BzlLoadValue.Key?>(),  // No parent value to mutate
                    /* childCachedDataHandler= */
                    java.util.function.Consumer { x: CachedBzlLoadData? -> })
            }
        }
    }

    /**
     * Entry point for compute logic that's common to both (bzl) inlining and non-inlining code paths.
     */
    // It is vital that we don't return any value if any call to env#getValue(s)OrThrow throws an
    // exception. We are allowed to wrap the thrown exception and rethrow it for any calling functions
    // to handle though.
    @Throws(BzlLoadFailedException::class, java.lang.InterruptedException::class)
    private fun computeInternal(
        key: BzlLoadValue.Key, env: SkyFunction.Environment, inliningState: InliningState?
    ): BzlLoadValue? {
        val label: Label = key.label
        val filePath: PathFragment? = label.toPathFragment()

        val builtins: StarlarkBuiltinsValue? = getBuiltins(key, env, inliningState)
        if (builtins == null) {
            return null
        }

        val compileKey: BzlCompileValue.Key? =
            validatePackageAndGetCompileKey(
                key,
                env,
                builtins.starlarkSemantics.get<String?>(BuildLanguageOptions.EXPERIMENTAL_BUILTINS_BZL_PATH)
            )
        if (compileKey == null) {
            return null
        }
        val compileValue: BzlCompileValue?
        try {
            compileValue = getter.getBzlCompileValue(compileKey, env)
        } catch (e: BzlCompileFunction.FailedIOException) {
            throw errorReadingBzl(filePath, e)
        }
        if (compileValue == null) {
            return null
        }

        var result: BzlLoadValue?
        // Release the compiled bzl iff the value gets completely evaluated (to either error or non-null
        // result).
        var completed = true
        try {
            result = computeInternalWithCompiledBzl(key, compileValue, builtins, env, inliningState)
            completed = result != null
        } finally {
            if (completed) { // only false on unexceptional null result
                getter.doneWithBzlCompileValue(compileKey)
            }
        }
        return result
    }

    /**
     * Obtain a suitable StarlarkBuiltinsValue.
     * 
     * 
     * For BUILD-loaded, WORKSPACE-loaded and *almost* all bzlmod-loaded .bzl files, this is a real
     * builtins value, obtained using either Skyframe or inlining of StarlarkBuiltinsFunction
     * (depending on whether `inliningState` is non-null). The returned value includes the
     * StarlarkSemantics.
     * 
     * 
     * For other .bzl files, the builtins computation is not needed and would create a Skyframe
     * cycle if requested, so we instead return an empty builtins value that just wraps the
     * StarlarkSemantics.
     */
    @Throws(BzlLoadFailedException::class, java.lang.InterruptedException::class)
    private fun getBuiltins(
        key: BzlLoadValue.Key?, env: SkyFunction.Environment, inliningState: InliningState?
    ): StarlarkBuiltinsValue? {
        if (!requiresBuiltinsInjection(key)) {
            val starlarkSemantics: net.starlark.java.eval.StarlarkSemantics? =
                PrecomputedValue.STARLARK_SEMANTICS.get(env)
            if (starlarkSemantics == null) {
                return null
            }
            return StarlarkBuiltinsValue.Companion.createEmpty(starlarkSemantics)
        }
        try {
            if (inliningState == null) {
                return env.getValueOrThrow<BuiltinsFailedException?>(
                    StarlarkBuiltinsValue.Companion.key(),
                    BuiltinsFailedException::class.java
                ) as StarlarkBuiltinsValue?
            } else {
                return StarlarkBuiltinsFunction.Companion.computeInline(
                    StarlarkBuiltinsValue.Companion.key(),
                    inliningState,
                    ruleClassProvider.getBazelStarlarkEnvironment(),  /* bzlLoadFunction= */
                    this
                )
            }
        } catch (e: BuiltinsFailedException) {
            throw builtinsFailed(key.label, e)
        }
    }

    /**
     * Given a bzl key, validates that the corresponding package exists (if required) and returns the
     * associated compile key based on the package's root. Returns null for a missing Skyframe dep or
     * unspecified exception.
     * 
     * 
     * .bzl files are not necessarily targets, because they can be loaded by BUILD and other .bzl
     * files without ever being declared in a BUILD file. However, .bzl files are still identified by
     * a label in the same way that file targets are. In particular, it is illegal to refer to a .bzl
     * file using a label whose package part is not the .bzl file's innermost containing package. For
     * example, if pkg and pkg/subpkg have BUILD files but not pkg/subdir, then `pkg/subdir:foo.bzl` and `pkg:subpkg/foo.bzl` are disallowed.
     * 
     * 
     * In the case of builtins .bzl files, all labels are written as if the pseudo-repo constitutes
     * one big package, e.g. `@builtins//:some/path/foo.bzl`, but no BUILD file need exist. The
     * compile key's root is determined by `--experimental_builtins_bzl_path` (passed as `builtinsBzlPath`) instead of by package lookup.
     */
    @Throws(BzlLoadFailedException::class, java.lang.InterruptedException::class)
    private fun validatePackageAndGetCompileKey(
        key: BzlLoadValue.Key, env: SkyFunction.Environment, builtinsBzlPath: String
    ): BzlCompileValue.Key? {
        val label: Label = key.label

        // Bypass package lookup entirely if builtins.
        if (key.isBuiltins()) {
            if (!label.getPackageName().isEmpty()) {
                throw noBuildFile(label, "@_builtins cannot have subpackages")
            }
            return key.getCompileKey(getBuiltinsRoot(builtinsBzlPath))
        }

        // Do package lookup.
        val dir: PathFragment? = Label.getContainingDirectory(label)
        val dirId: PackageIdentifier? = PackageIdentifier.create(label.getRepository(), dir)
        val packageLookup: ContainingPackageLookupValue?
        try {
            packageLookup =
                env.getValueOrThrow<E1?, E2?>(
                    ContainingPackageLookupValue.key(dirId),
                    BuildFileNotFoundException::class.java,
                    InconsistentFilesystemException::class.java
                ) as ContainingPackageLookupValue?
        } catch (e: BuildFileNotFoundException) {
            throw errorFindingContainingPackage(label.toPathFragment(), e)
        } catch (e: InconsistentFilesystemException) {
            throw errorFindingContainingPackage(label.toPathFragment(), e)
        }
        if (packageLookup == null) {
            return null
        }

        // Resolve to compile key or error.
        val compileKey: BzlCompileValue.Key?
        val packageOk =
            packageLookup.hasContainingPackage()
                    && packageLookup.containingPackageName.equals(label.getPackageIdentifier())
        if (key.isBuildPrelude() && !packageOk) {
            // Ignore the prelude, its package doesn't exist.
            compileKey = BzlCompileValue.EMPTY_PRELUDE_KEY
        } else {
            if (packageOk) {
                compileKey = key.getCompileKey(packageLookup.containingPackageRoot)
            } else {
                if (!packageLookup.hasContainingPackage()) {
                    throw noBuildFile(label, packageLookup.getReasonForNoContainingPackage())
                } else {
                    throw labelCrossesPackageBoundary(label, packageLookup)
                }
            }
        }
        return compileKey
    }

    private fun getBuiltinsRoot(builtinsBzlPath: String): Root {
        // TODO(#11437): Remove once injection can't be disabled.
        check(!builtinsBzlPath.isEmpty()) { "Requested builtins root, but injection is disabled" }

        // TODO(#11437): For the non-bundled case, should we consider interning the Root rather than
        // constructing a new one each time?
        val root: Root
        if (builtinsBzlPath == "%bundled%") {
            // May be null in tests, but in that case the builtins path shouldn't be set to %bundled%.
            root =
                com.google.common.base.Preconditions.checkNotNull(
                    ruleClassProvider.getBundledBuiltinsRoot(),
                    ("rule class provider does not specify a builtins root; either call"
                            + " setBuiltinsBzlZipResource() or else set --experimental_builtins_bzl_path to"
                            + " a root")
                )
        } else if (builtinsBzlPath == "%workspace%") {
            val packagePath: String =
                com.google.common.base.Preconditions.checkNotNull(
                    ruleClassProvider.getBuiltinsBzlPackagePathInSource(),
                    ("rule class provider does not specify a canonical package path to a builtins root;"
                            + " either call setBuiltinsBzlPackagePathInSource() or else do not set"
                            + "--experimental_builtins_bzl_path to %workspace%")
                )
            // TODO(brandjon): Here we return a root that is underneath a package root. Since the root is
            // itself not a package root, we don't get the benefit of any special DiffAwareness handling.
            // This case probably isn't important since it doesn't occur in production Bazel, but
            // presumably we might be able to add a special DiffAwareness for it if we wanted.
            root = Root.fromPath(directories.getWorkspace().getRelative(packagePath))
        } else {
            root = Root.fromPath(directories.getWorkspace().getRelative(builtinsBzlPath))
        }
        return root
    }

    /**
     * Compute logic for once the compiled .bzl has been fetched and confirmed to exist (though it may
     * have Starlark errors).
     */
    @Throws(BzlLoadFailedException::class, java.lang.InterruptedException::class)
    private fun computeInternalWithCompiledBzl(
        key: BzlLoadValue.Key,
        compileValue: BzlCompileValue,
        builtins: StarlarkBuiltinsValue,
        env: SkyFunction.Environment,
        inliningState: InliningState?
    ): BzlLoadValue? {
        // Ensure the .bzl exists and passes static checks (parsing, resolving).
        // (A missing prelude file still returns a valid but empty BzlCompileValue.)
        if (!compileValue.lookupSuccessful()) {
            throw BzlLoadFailedException(compileValue.error, Code.COMPILE_ERROR)
        }
        var prog: net.starlark.java.syntax.Program = compileValue.program
        val label: Label = key.label
        val pkg: PackageIdentifier = label.getPackageIdentifier()

        val isSclFlagEnabled: Boolean =
            builtins.starlarkSemantics.getBool(BuildLanguageOptions.EXPERIMENTAL_ENABLE_SCL_DIALECT)
        if (key.isSclDialect() && !isSclFlagEnabled) {
            throw BzlLoadFailedException(
                "loading .scl files requires setting --experimental_enable_scl_dialect",
                Code.PARSE_ERROR
            )
        }

        // Determine dependency BzlLoadValue keys for the load statements in this bzl.
        // Labels are resolved relative to the current repo mapping.
        val repoMapping: RepositoryMapping? = getRepositoryMapping(key, env)
        if (repoMapping == null) {
            return null
        }
        val mainRepoMapping: RepositoryMapping? = getMainRepositoryMapping(key, env)
        if (mainRepoMapping == null) {
            return null
        }
        val repoMappingRecorder: Label.SimpleRepoMappingRecorder = SimpleRepoMappingRecorder()
        val programLoads: com.google.common.collect.ImmutableList<com.google.devtools.build.lib.util.Pair<String?, net.starlark.java.syntax.Location?>> =
            getLoadsFromProgram(prog)
        val loadLabels: com.google.common.collect.ImmutableList<Label?>? =
            getLoadLabels(
                env.getListener(),
                programLoads,
                pkg,
                ruleClassProvider::isPackageUnderExperimental,
                ruleClassProvider::isPackageUnderPrototypes,
                ruleClassProvider::mayPackageDependOnPrototypes,
                builtins.starlarkSemantics.getBool(BuildLanguageOptions.ALLOW_EXPERIMENTAL_LOADS),
                repoMapping,
                key.isSclDialect(),
                isSclFlagEnabled,
                repoMappingRecorder
            )
        if (loadLabels == null) {
            throw BzlLoadFailedException(
                java.lang.String.format(
                    "module '%s'%s has invalid load statements",
                    label.toPathFragment(),
                    if (StarlarkBuiltinsValue.Companion.isBuiltinsRepo(label.getRepository())) " (internal)" else ""
                ),
                Code.PARSE_ERROR
            )
        }
        val loadKeysBuilder: com.google.common.collect.ImmutableList.Builder<BzlLoadValue.Key?> =
            com.google.common.collect.ImmutableList.builderWithExpectedSize<BzlLoadValue.Key?>(loadLabels.size())
        for (loadLabel in loadLabels) {
            loadKeysBuilder.add(key.getKeyForLoad(loadLabel))
        }
        val loadKeys: com.google.common.collect.ImmutableList<BzlLoadValue.Key?> = loadKeysBuilder.build()

        // Load .bzl modules.
        // When not using bzl inlining, this is done in parallel for all loads.
        val loadValues: MutableList<BzlLoadValue>? =
            if (inliningState == null)
                computeBzlLoadsWithSkyframe(env, loadKeys, programLoads)
            else
                computeBzlLoadsWithInlining(env, loadKeys, programLoads, inliningState)
        if (loadValues == null) {
            return null // Skyframe deps unavailable
        }

        // Validate that the current .bzl file satisfies each loaded dependency's load visibility.
        // Violations are reported as error events (since there can be more than one in a single file)
        // and also trigger a BzlLoadFailedException.
        checkLoadVisibilities(
            pkg,
            "module " + label.getCanonicalForm(),
            loadValues,
            loadKeys,
            programLoads,  /* demoteErrorsToWarnings= */
            !builtins.starlarkSemantics.getBool(
                BuildLanguageOptions.CHECK_BZL_VISIBILITY
            ),
            ruleClassProvider::isPackageUnderExperimental,
            ruleClassProvider::isPackageUnderPrototypes,
            env.getListener()
        )

        // Accumulate a transitive digest of the bzl file, the digests of its direct loads, and the
        // digest of the @_builtins pseudo-repository (if applicable).
        val fp: Fingerprint = Fingerprint()
        fp.addBytes(compileValue.digest)

        // Populate the load map and add transitive digests to the fingerprint.
        val loadMap: MutableMap<String?, net.starlark.java.eval.Module?> =
            com.google.common.collect.Maps.newLinkedHashMapWithExpectedSize<String?, net.starlark.java.eval.Module?>(
                programLoads.size()
            )
        var i = 0
        for (load in programLoads) {
            val v: BzlLoadValue = loadValues.get(i++)
            loadMap.put(load.first, v.getModule()) // dups ok
            fp.addBytes(v.transitiveDigest)
            repoMappingRecorder.record(v.recordedRepoMappings)
        }

        // Retrieve predeclared symbols and complete the digest computation.
        val predeclared: com.google.common.collect.ImmutableMap<String?, Any?>? =
            getAndDigestPredeclaredEnvironment(key, builtins, fp)
        if (predeclared == null) {
            return null
        }
        val transitiveDigest: ByteArray? = fp.digestAndReset()

        // The BazelModuleContext holds additional contextual info to be associated with the Module,
        // including the label and a reified copy of the load DAG.
        val bazelModuleContext: BazelModuleContext? =
            BazelModuleContext.create(
                key,
                repoMapping,
                prog.getFilename(),
                com.google.common.collect.ImmutableList.< E > copyOf < E ? > (loadMap.values()),
                transitiveDigest,
                prog.getDocCommentsMap(),
                prog.getUnusedDocCommentLines()
            )

        // Construct the initial Starlark module used for executing the program.
        // The set of keys in the predeclared environment matches the set of predeclareds used to
        // compile the .bzl file into a Program.
        val module: net.starlark.java.eval.Module =
            net.starlark.java.eval.Module.withPredeclaredAndData(
                builtins.starlarkSemantics,
                predeclared,
                bazelModuleContext
            )

        // Type-tag and type-check the program
        val typeOptions: BzlCompileValue.TypeOptions = compileValue.typeOptions
        if (typeOptions.wantStaticTypeChecking || typeOptions.wantDynamicTypeChecking) {
            try {
                prog =
                    net.starlark.java.eval.Starlark.withTypeInfo(
                        prog,
                        module,
                        typeOptions.wantStaticTypeChecking,
                        net.starlark.java.syntax.TypeTagger.Loader { key: String? -> loadMap.get(key) })
            } catch (e: net.starlark.java.syntax.SyntaxError.Exception) {
                com.google.devtools.build.lib.events.Event.replayEventsOn(env.getListener(), e.errors())
                throw typingFailed(label)
            }
        }

        // The BzlInitThreadContext holds Starlark thread-local state to be read and updated during
        // evaluation.
        val context: BzlInitThreadContext =
            BzlInitThreadContext(
                label,
                transitiveDigest,
                ruleClassProvider.toolsRepository,
                ruleClassProvider.getNetworkAllowlistForTests(),
                ruleClassProvider.getConfigurationFragmentMap(),
                mainRepoMapping
            )

        // executeBzlFile may post events to the Environment's handler, but events do not matter when
        // caching BzlLoadValues. Note that executing the code mutates the Module and
        // BzlInitThreadContext.
        executeBzlFile(
            prog,
            key,
            module,
            loadMap,
            context,
            builtins.starlarkSemantics,
            env.getListener(),
            repoMappingRecorder
        )

        var bzlVisibility: BzlVisibility? = context.getBzlVisibility()
        if (bzlVisibility == null) {
            bzlVisibility = BzlVisibility.PUBLIC
        }
        // We save load visibility in the BzlLoadValue rather than the BazelModuleContext because
        // visibility doesn't need to be introspected by any Starlark builtin methods, and because the
        // alternative would mean mutating or overwriting the BazelModuleContext after evaluation.
        return BzlLoadValue(
            module, transitiveDigest, bzlVisibility, repoMappingRecorder.recordedEntries()
        )
    }

    /**
     * Computes the BzlLoadValue for all given keys by reusing this instance of the BzlLoadFunction,
     * bypassing traditional Skyframe evaluation. `programLoads` provides the locations of the
     * load statements in source order, for error reporting.
     * 
     * @return null if there was a missing Skyframe dep, an unspecified exception in a Skyframe dep
     * request, or if this was a duplicate unsuccessful visitation
     */
    @Throws(BzlLoadFailedException::class, java.lang.InterruptedException::class)
    private fun computeBzlLoadsWithInlining(
        env: SkyFunction.Environment?,
        keys: MutableList<BzlLoadValue.Key?>,
        programLoads: MutableList<com.google.devtools.build.lib.util.Pair<String?, net.starlark.java.syntax.Location?>>,
        inliningState: InliningState
    ): MutableList<BzlLoadValue?>? {
        com.google.common.base.Preconditions.checkState(env === inliningState.recordingEnv)

        val bzlLoads: MutableList<BzlLoadValue?> =
            com.google.common.collect.Lists.newArrayListWithExpectedSize<BzlLoadValue?>(keys.size())
        // For the sake of ensuring the graph structure is deterministic, we need to request all of our
        // deps, even if some of them yield errors. The first exception that is seen gets deferred, to
        // be raised after the loop. All other exceptions are swallowed.
        //
        // To see how immediately returning the first error leads to non-determinism, consider the case
        // of two dependencies A and B, where A is in error and appears in a load statement above B.
        // If A has completed at the time we request it, and if we were to immediately propagate that
        // error, we never request B. On the other hand, if A is missing (null return), we do request B
        // in the meantime for the sake of parallelism.
        //
        // This approach assumes --keep_going; determinism is not guaranteed otherwise. It also assumes
        // InterruptedException does not occur, since we don't catch and defer it.
        var deferredException: BzlLoadFailedException? = null
        var valuesMissing = false
        for (i in keys.indices) {
            val cachedData: CachedBzlLoadData?
            try {
                cachedData = computeInlineCachedData(keys.get(i), inliningState)
            } catch (e: BzlLoadFailedException) {
                if (deferredException == null) {
                    deferredException = whileLoadingDep(programLoads.get(i).second, e)
                }
                continue
            }
            if (cachedData == null) {
                // A null value for `cachedData` can occur when it (or its transitive loads) has a Skyframe
                // dep that is missing or in error. It can also occur if there's a transitive load on a bzl
                // that was already seen by inliningState and which returned null (note: in this case, it's
                // not necessarily true that there are missing Skyframe deps because this bzl could have
                // already been visited unsuccessfully). In both these cases, we want to continue making our
                // inline calls, so as to maximize the number of dependent (non-inlined) SkyFunctions that
                // are requested and avoid a quadratic number of restarts.
                valuesMissing = true
            } else {
                bzlLoads.add(cachedData.getValue())
            }
        }
        if (deferredException != null) {
            throw deferredException
        }
        return if (valuesMissing) null else bzlLoads
    }

    /**
     * Obtains the predeclared environment for a .bzl (or .scl) file, based on the type of .bzl and
     * (if applicable) the injected builtins.
     * 
     * 
     * Returns null if there was a missing Skyframe dep or unspecified exception.
     * 
     * 
     * In the case that injected builtins are used, updates the given fingerprint with the digest
     * of the `@_builtins` pseudo-repository.
     */
    private fun getAndDigestPredeclaredEnvironment(
        key: BzlLoadValue.Key, builtins: StarlarkBuiltinsValue, fp: Fingerprint
    ): com.google.common.collect.ImmutableMap<String?, Any?>? {
        val starlarkEnv: BazelStarlarkEnvironment = ruleClassProvider.getBazelStarlarkEnvironment()
        if (key.isSclDialect()) {
            // .scl doesn't use injection and doesn't care what kind of key it is.
            return starlarkEnv.getStarlarkGlobals().getSclToplevels()
        } else {
            // TODO(#11437): Remove ability to disable injection by setting flag to empty string.
            val injectionDisabled: Boolean =
                builtins
                    .starlarkSemantics
                    .get<String?>(BuildLanguageOptions.EXPERIMENTAL_BUILTINS_BZL_PATH)
                    .isEmpty()
            if (key is BzlLoadValue.KeyForBuild) {
                if (injectionDisabled) {
                    return starlarkEnv.getUninjectedBuildBzlEnv()
                }
                fp.addBytes(builtins.transitiveDigest)
                return builtins.predeclaredForBuildBzl
            } else if (key is BzlLoadValue.KeyForBzlmod) {
                // TODO(#11954): We should converge all .bzl dialects regardless of whether they're loaded
                //  by BUILD or MODULE.
                if (injectionDisabled || key is BzlLoadValue.KeyForBzlmodBootstrap) {
                    return starlarkEnv.getUninjectedModuleBzlEnv()
                }
                // Note that we don't actually fingerprint the injected builtins here. The actual builtins
                // values should not be used in MODULE-loaded .bzl files; they're only injected to avoid
                // certain type errors at loading time (e.g. #17713). If we included their digest, we'd be
                // causing widespread repo refetches when _any_ builtin bzl file changes (when Bazel
                // upgrades, for example), and potentially even thrashing if the user is using Bazelisk.
                // Thus we make the explicit choice to not fingerprint the injected builtins, and thereby
                // prohibit any meaningful use of injected builtins in MODULE-loaded .bzl files. This
                // additionally means that native repo rules should not be migrated to @_builtins; they
                // should just live in @bazel_tools instead.
                return builtins.predeclaredForModuleBzl
            } else if (key is BzlLoadValue.KeyForBuiltins) {
                return starlarkEnv.getBuiltinsBzlEnv()
            } else {
                throw java.lang.AssertionError("Unknown key type: " + key.getClass())
            }
        }
    }

    /**
     * A manager abstracting over the method for obtaining `BzlCompileValue`s. See comment in
     * [.create].
     */
    private interface ValueGetter {
        @Throws(BzlCompileFunction.FailedIOException::class, java.lang.InterruptedException::class)
        fun getBzlCompileValue(key: BzlCompileValue.Key?, env: SkyFunction.Environment?): BzlCompileValue?

        fun doneWithBzlCompileValue(key: BzlCompileValue.Key?)
    }

    /** A manager that obtains compiled .bzl files from Skyframe calls.  */
    private class RegularSkyframeGetter : ValueGetter {
        @Throws(BzlCompileFunction.FailedIOException::class, java.lang.InterruptedException::class)
        override fun getBzlCompileValue(key: BzlCompileValue.Key?, env: SkyFunction.Environment): BzlCompileValue? {
            return env.getValueOrThrow<E?>(key, BzlCompileFunction.FailedIOException::class.java) as BzlCompileValue?
        }

        override fun doneWithBzlCompileValue(key: BzlCompileValue.Key?) {}

        companion object {
            private val INSTANCE = RegularSkyframeGetter()
        }
    }

    /**
     * A manager that obtains compiled .bzls by inlining [BzlCompileFunction] (not to be
     * confused with inlining of `BzlLoadFunction`). Values are cached within the manager and
     * released explicitly by calling [.doneWithBzlCompileValue].
     */
    private class InliningAndCachingGetter(
        ruleClassProvider: RuleClassProvider,
        hashFunction: com.google.common.hash.HashFunction?,
        packageLoadingListener: PackageLoadingListener?,
        bzlCompileCache: com.github.benmanes.caffeine.cache.Cache<BzlCompileValue.Key?, BzlCompileValue?>
    ) : ValueGetter {
        private val ruleClassProvider: RuleClassProvider
        private val hashFunction: com.google.common.hash.HashFunction?
        private val packageLoadingListener: PackageLoadingListener?

        // We keep a cache of BzlCompileValues that have been computed but whose corresponding
        // BzlLoadValue has not yet completed. This avoids repeating the BzlCompileValue work in case
        // of Skyframe restarts. (If we weren't inlining, Skyframe would cache this for us.)
        private val bzlCompileCache: com.github.benmanes.caffeine.cache.Cache<BzlCompileValue.Key?, BzlCompileValue?>

        init {
            this.ruleClassProvider = ruleClassProvider
            this.hashFunction = hashFunction
            this.packageLoadingListener = packageLoadingListener
            this.bzlCompileCache = bzlCompileCache
        }

        @Throws(BzlCompileFunction.FailedIOException::class, java.lang.InterruptedException::class)
        override fun getBzlCompileValue(key: BzlCompileValue.Key?, env: SkyFunction.Environment?): BzlCompileValue? {
            var value: BzlCompileValue? = bzlCompileCache.getIfPresent(key)
            if (value == null) {
                value =
                    BzlCompileFunction.computeInline(
                        key,
                        env,
                        ruleClassProvider.getBazelStarlarkEnvironment(),
                        hashFunction,
                        packageLoadingListener
                    )
                if (value != null) {
                    bzlCompileCache.put(key, value)
                }
            }
            return value
        }

        override fun doneWithBzlCompileValue(key: BzlCompileValue.Key?) {
            bzlCompileCache.invalidate(key)
        }
    }

    /**
     * Per-instance manager for [CachedBzlLoadData], used when `BzlLoadFunction` calls are
     * inlined.
     */
    internal class InlineCacheManager private constructor(private val bzlLoadCacheSize: Int) {
        // Data which will be cleared on #reset().
        private var bzlLoadCache: com.github.benmanes.caffeine.cache.Cache<BzlLoadValue.Key?, CachedBzlLoadData?>? =
            null
        private var cachedBzlLoadDataBuilderFactory: CachedBzlLoadDataBuilderFactory = CachedBzlLoadDataBuilderFactory()

        // Not private so that StarlarkBuiltinsFunction can directly access this.
        var builtinsRef: AtomicReference<StarlarkBuiltinsValue?> = AtomicReference<StarlarkBuiltinsValue?>()

        private fun cachedDataBuilder(): CachedBzlLoadData.Builder {
            return cachedBzlLoadDataBuilderFactory.newCachedBzlLoadDataBuilder()
        }

        private fun reset(resetBuiltins: Boolean) {
            if (bzlLoadCache != null) {
                logger.atInfo().log(
                    "Starlark inlining cache stats from earlier build: %s", bzlLoadCache.stats()
                )
            }
            cachedBzlLoadDataBuilderFactory = CachedBzlLoadDataBuilderFactory()
            com.google.common.base.Preconditions.checkState(
                bzlLoadCacheSize >= 0,
                "Expected positive Starlark cache size if caching. %s",
                bzlLoadCacheSize
            )
            bzlLoadCache =
                Caffeine.newBuilder()
                    .initialCapacity(BlazeInterners.concurrencyLevel())
                    .maximumSize(bzlLoadCacheSize.toLong())
                    .recordStats()
                    .build<BzlLoadValue.Key?, CachedBzlLoadData?>()

            // All actual usages of BzlLoadFunction inlining assume builtins can never change (i.e. no
            // usage of --experimental_builtins_bzl_path, no inter-invocation flipping of Starlark
            // semantics options that'd cause evaluation of builtins to differ). This assumption is only
            // violated in some tests, which rewrite the builtins to validate the state. If non-test usage
            // can ever change builtins, we'd also want to inline deps on the logical Skyframe subgraph
            // when we get a `builtins` cache hit.
            if (resetBuiltins) {
                builtinsRef = AtomicReference<StarlarkBuiltinsValue?>()
            }
        }
    }

    private class BzlLoadFunctionException(cause: BzlLoadFailedException) :
        SkyFunctionException(cause, cause.getTransience())

    companion object {
        private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()

        fun create(
            ruleClassProvider: RuleClassProvider,
            directories: BlazeDirectories,
            hashFunction: com.google.common.hash.HashFunction?,
            packageLoadingListener: PackageLoadingListener?,
            bzlCompileCache: com.github.benmanes.caffeine.cache.Cache<BzlCompileValue.Key?, BzlCompileValue?>
        ): BzlLoadFunction {
            return BzlLoadFunction(
                ruleClassProvider,
                directories,  // When we are not inlining BzlLoadValue nodes, there is no need to have separate
                // BzlCompileValue nodes for bzl files. Instead we inline BzlCompileFunction for a
                // strict memory win, at a small code complexity cost.
                //
                // Detailed explanation:
                // (1) The BzlCompileValue node for a bzl file is used only for the computation of
                // that file's BzlLoadValue node. So there's no concern about duplicate work that would
                // otherwise get deduped by Skyframe.
                // (2) BzlCompileValue doesn't have an interesting equality relation, so we have no
                // hope of getting any interesting change-pruning of BzlCompileValue nodes. If we
                // had an interesting equality relation that was e.g. able to ignore benign
                // whitespace, then there would be a hypothetical benefit to having separate
                // BzlCompileValue nodes (e.g. on incremental builds we'd be able to not re-execute
                // top-level code in bzl files if the file were reparsed to an equivalent tree).
                // TODO(adonovan): this will change once it truly compiles the code (soon).
                // (3) A BzlCompileValue node lets us avoid redoing work on a BzlLoadFunction Skyframe
                // restart, but we can also achieve that result ourselves with a cache that persists between
                // Skyframe restarts.
                //
                // Therefore, BzlCompileValue nodes are wasteful from two perspectives:
                // (a) BzlCompileValue contains syntax trees, and that business object is really
                // just a temporary thing for bzl execution. Retaining it forever is pure waste.
                // (b) The memory overhead of the extra Skyframe node and edge per bzl file is pure
                // waste.
                InliningAndCachingGetter(
                    ruleClassProvider, hashFunction, packageLoadingListener, bzlCompileCache
                ),  /* inlineCacheManager= */
                null
            )
        }

        /**
         * Constructs a new instance that uses bzl inlining.
         * 
         * 
         * Must call [.resetInliningCache] on the returned instance before use.
         */
        fun createForInlining(
            ruleClassProvider: RuleClassProvider,
            directories: BlazeDirectories,
            bzlLoadValueCacheSize: Int
        ): BzlLoadFunction {
            return BzlLoadFunction(
                ruleClassProvider,
                directories,  // When we are inlining BzlLoadValue nodes, then we want to have explicit BzlCompileValue
                // nodes, since now (1) in the comment above doesn't hold. This way we read and parse each
                // needed bzl file at most once total globally, rather than once per need (in the worst-case
                // of a BzlLoadValue inlining cache miss). This is important in the situation where a bzl
                // file is loaded by a lot of other bzl files or BUILD files.
                RegularSkyframeGetter.Companion.INSTANCE,
                InlineCacheManager(bzlLoadValueCacheSize)
            )
        }

        private fun requiresBuiltinsInjection(key: BzlLoadValue.Key?): Boolean {
            return key is BzlLoadValue.KeyForBuild // https://github.com/bazelbuild/bazel/issues/17713
                    // `@_builtins` depends on `@bazel_tools` for repo mapping, so we ignore some bzl files
                    // to avoid a cyclic dependency
                    || (key is BzlLoadValue.KeyForBzlmod
                    && key !is BzlLoadValue.KeyForBzlmodBootstrap)
        }

        @Throws(java.lang.InterruptedException::class)
        private fun getRepositoryMapping(key: BzlLoadValue.Key, env: SkyFunction.Environment): RepositoryMapping? {
            val repoName: RepositoryName? = key.label.getRepository()

            if (key is BzlLoadValue.KeyForBzlmodBootstrap) {
                // Special case: we're only here to get one of the rules in the @bazel_tools repo that
                // load Bazel modules. At this point we can't load from any other modules and thus use a
                // repository mapping that contains only @bazel_tools itself.
                return RepositoryMapping.create(
                    com.google.common.collect.ImmutableMap.of<K?, V?>("bazel_tools", RepositoryName.BAZEL_TOOLS),
                    RepositoryName.BAZEL_TOOLS
                )
            }

            // This is either a .bzl loaded from BUILD files, or a .bzl loaded for bzlmod, so we can just
            // use the full repo mapping from RepositoryMappingFunction.
            val repositoryMappingValue: RepositoryMappingValue? =
                env.getValue(RepositoryMappingValue.key(repoName)) as RepositoryMappingValue?
            if (repositoryMappingValue == null) {
                return null
            }
            return repositoryMappingValue.repositoryMapping()
        }

        @Throws(java.lang.InterruptedException::class)
        private fun getMainRepositoryMapping(key: BzlLoadValue.Key, env: SkyFunction.Environment): RepositoryMapping? {
            if (key is BzlLoadValue.KeyForBuiltins
                || key is BzlLoadValue.KeyForBzlmodBootstrap
            ) {
                // For builtins and @bazel_tools, the key's local repo mapping can be used as the main repo
                // mapping.
                return getRepositoryMapping(key, env)
            }
            val mainRepositoryMappingValue: RepositoryMappingValue? =
                env.getValue(RepositoryMappingValue.key(RepositoryName.MAIN)) as RepositoryMappingValue?
            if (mainRepositoryMappingValue == null) {
                return null
            }
            return mainRepositoryMappingValue.repositoryMapping()
        }

        /**
         * Validates a label appearing in a `load()` statement, throwing [ ] on failure.
         * 
         * 
         * Different restrictions apply depending on what type of source file the load appears in. For
         * all kinds of files, `label`:
         * 
         * 
         *  * may not be within `@//external`.
         *  * must end with either `.bzl` or `.scl`.
         * 
         * 
         * 
         * For source files appearing within `@_builtins`, `label` must also be within
         * `@_builtins`. (The reverse, that those files may not be loaded by user-defined files, is
         * enforced by the fact that the `@_builtins` pseudorepo cannot be resolved as an ordinary
         * repo.)
         * 
         * 
         * For .scl files only, `label` must end with `.scl` (not `.bzl`). (Loads in
         * .scl also should always begin with `//`, but that's syntactic and can't be enforced in
         * this method.)
         * 
         * @param label the label to validate
         * @param fromBuiltinsRepo true if the file containing the load is within `@_builtins`
         * @param withinSclDialect true if the file containing the load is a .scl file
         * @param mentionSclInErrorMessage true if ".scl" should be advertised as a possible extension in
         * error messaging
         */
        @Throws(LabelSyntaxException::class)
        private fun checkValidLoadLabel(
            label: Label,
            fromBuiltinsRepo: Boolean,
            withinSclDialect: Boolean,
            mentionSclInErrorMessage: Boolean
        ) {
            // Check file extension.
            val baseName: String = label.name
            if (withinSclDialect) {
                if (!baseName.endsWith(".scl")) {
                    var msg = "The label must reference a file with extension \".scl\""
                    if (baseName.endsWith(".bzl")) {
                        msg += " (.scl files cannot load .bzl files)"
                    }
                    throw LabelSyntaxException(msg)
                }
            } else {
                if (!(baseName.endsWith(".scl") || baseName.endsWith(".bzl"))) {
                    var msg = "The label must reference a file with extension \".bzl\""
                    if (mentionSclInErrorMessage) {
                        msg += " or \".scl\""
                    }
                    throw LabelSyntaxException(msg)
                }
            }

            if (label.getPackageIdentifier().equals(LabelConstants.EXTERNAL_PACKAGE_IDENTIFIER)) {
                throw LabelSyntaxException(
                    "Starlark files may not be loaded from the //external package"
                )
            }
            if (fromBuiltinsRepo && !StarlarkBuiltinsValue.Companion.isBuiltinsRepo(label.getRepository())) {
                throw LabelSyntaxException(
                    ".bzl files in @_builtins cannot load from outside of @_builtins"
                )
            }
        }

        /**
         * Validates a label appearing in a `load()` statement, throwing [ ] on failure.
         * 
         * 
         * This does not enforce restrictions on loading experimental .bzls (`--allow_experimental_loads`).
         */
        @Throws(LabelSyntaxException::class)
        fun checkValidLoadLabel(label: Label, starlarkSemantics: net.starlark.java.eval.StarlarkSemantics) {
            checkValidLoadLabel(
                label,  /* fromBuiltinsRepo= */
                false,  /* withinSclDialect= */
                false,  /* mentionSclInErrorMessage= */
                starlarkSemantics.getBool(
                    BuildLanguageOptions.EXPERIMENTAL_ENABLE_SCL_DIALECT
                )
            )
        }

        /**
         * Given a list of `load("module")` strings and their locations, in source order, returns a
         * corresponding list of Labels they each resolve to. Labels are resolved relative to `base`, the file's package. If any label is malformed, the function reports one or more errors
         * to the handler and returns null.
         * 
         * 
         * If `allowExperimentalLoads` is false, a load of an experimental bzl is only tolerated
         * if the `base` package is also experimental (as determined by the `isUnderExperimental` predicate).
         * 
         * 
         * If `withinSclDialect` is true, the labels are validated according to the rules of the
         * .scl dialect: Only strings beginning with `//` are allowed (no repo syntax, no relative
         * labels), and only .scl files may be loaded (not .bzl). If `isSclFlagEnabled` is true,
         * then ".scl" is mentioned as a possible file extension in error messages.
         */
        @com.google.common.annotations.VisibleForTesting
        fun getLoadLabels(
            handler: com.google.devtools.build.lib.events.EventHandler,
            loads: com.google.common.collect.ImmutableList<com.google.devtools.build.lib.util.Pair<String?, net.starlark.java.syntax.Location?>>,
            base: PackageIdentifier,
            isUnderExperimental: java.util.function.Predicate<PackageIdentifier?>,
            isUnderPrototypes: java.util.function.Predicate<PackageIdentifier?>,
            mayDependOnPrototypes: java.util.function.Predicate<PackageIdentifier?>,
            allowExperimentalLoads: Boolean,
            repoMapping: RepositoryMapping?,
            withinSclDialect: Boolean,
            isSclFlagEnabled: Boolean,
            repoMappingRecorder: Label.RepoMappingRecorder?
        ): com.google.common.collect.ImmutableList<Label?>? {
            var ok = true

            val loadLabels: com.google.common.collect.ImmutableList.Builder<Label?> =
                com.google.common.collect.ImmutableList.builderWithExpectedSize<Label?>(loads.size())
            for (load in loads) {
                // Parse the load statement's module string as a label. Validate the unparsed string for
                // syntax and the parsed label for structure.
                var unparsedLabel: String? = load.first
                try {
                    if (withinSclDialect) {
                        if (!unparsedLabel.startsWith("//")) {
                            throw LabelSyntaxException("in .scl files, load labels must begin with \"//\"")
                        }
                        // Map the magic label "//:project_proto.scl" to the corresponding label in bazel_tools,
                        // since .scl doesn't support @repo syntax.
                        // See https://github.com/bazelbuild/bazel/issues/24839
                        if (unparsedLabel == "//:project_proto.scl") {
                            unparsedLabel = "@bazel_tools//src/main/protobuf/project:project_proto.scl"
                        }
                    }
                    val label: Label =
                        Label.parseWithPackageContext(
                            unparsedLabel, PackageContext.of(base, repoMapping), repoMappingRecorder
                        )
                    checkValidLoadLabel(
                        label,  /* fromBuiltinsRepo= */
                        StarlarkBuiltinsValue.Companion.isBuiltinsRepo(base.getRepository()),  /* withinSclDialect= */
                        withinSclDialect,  /* mentionSclInErrorMessage= */
                        isSclFlagEnabled
                    )
                    if (!allowExperimentalLoads && isUnderExperimental.test(label.getPackageIdentifier())
                        && !isUnderExperimental.test(base)
                    ) {
                        throw LabelSyntaxException(
                            """
              Cannot load an experimental Starlark file from a non-experimental package.
              Consider moving the loaded file to a non-experimental package.
              To temporarily bypass this error, use --allow_experimental_loads.
              
              """.trimIndent()
                        )
                    }
                    if (isUnderPrototypes.test(label.getPackageIdentifier())
                        && !mayDependOnPrototypes.test(base)
                    ) {
                        throw LabelSyntaxException(
                            "Cannot load a Starlark file under prototypes from a non-experimental, non-prototypes"
                                    + " package. Consider moving the loaded file to a non-prototype package."
                        )
                    }
                    loadLabels.add(label)
                } catch (ex: LabelSyntaxException) {
                    handler.handle(
                        com.google.devtools.build.lib.events.Event.error(
                            load.second,
                            "in load statement: " + ex.getMessage()
                        )
                    )
                    ok = false
                }
            }
            return if (ok) loadLabels.build() else null
        }

        /**
         * Given a list of `load("module")` strings and their locations, in source order, returns a
         * corresponding list of Labels they each resolve to. Labels are resolved relative to `base`, the file's package. If any label is malformed, the function reports one or more errors
         * to the handler and returns null.
         */
        fun getLoadLabels(
            handler: com.google.devtools.build.lib.events.EventHandler,
            loads: com.google.common.collect.ImmutableList<com.google.devtools.build.lib.util.Pair<String?, net.starlark.java.syntax.Location?>>,
            base: PackageIdentifier,
            isUnderExperimental: java.util.function.Predicate<PackageIdentifier?>,
            isUnderPrototypes: java.util.function.Predicate<PackageIdentifier?>,
            mayDependOnPrototypes: java.util.function.Predicate<PackageIdentifier?>,
            repoMapping: RepositoryMapping?,
            starlarkSemantics: net.starlark.java.eval.StarlarkSemantics
        ): com.google.common.collect.ImmutableList<Label?>? {
            return getLoadLabels(
                handler,
                loads,
                base,
                isUnderExperimental,
                isUnderPrototypes,
                mayDependOnPrototypes,  /* allowExperimentalLoads= */
                starlarkSemantics.getBool(
                    BuildLanguageOptions.ALLOW_EXPERIMENTAL_LOADS
                ),
                repoMapping,  /* withinSclDialect= */
                false,  /* isSclFlagEnabled= */
                starlarkSemantics.getBool(
                    BuildLanguageOptions.EXPERIMENTAL_ENABLE_SCL_DIALECT
                ),  /* repoMappingRecorder= */
                null
            )
        }

        /** Extracts load statements from compiled program (see [.getLoadLabels]).  */
        fun getLoadsFromProgram(prog: net.starlark.java.syntax.Program): com.google.common.collect.ImmutableList<com.google.devtools.build.lib.util.Pair<String?, net.starlark.java.syntax.Location?>> {
            val n: Int = prog.getLoads().size()
            val loads: com.google.common.collect.ImmutableList.Builder<com.google.devtools.build.lib.util.Pair<String?, net.starlark.java.syntax.Location?>?> =
                com.google.common.collect.ImmutableList.builderWithExpectedSize<com.google.devtools.build.lib.util.Pair<String?, net.starlark.java.syntax.Location?>?>(
                    n
                )
            for (i in 0..<n) {
                loads.add(
                    com.google.devtools.build.lib.util.Pair.of<String?, net.starlark.java.syntax.Location?>(
                        prog.getLoads().get(i), prog.getLoadLocation(i)
                    )
                )
            }
            return loads.build()
        }

        /** Extracts load statements from file syntax (see [.getLoadLabels]).  */
        fun getLoadsFromStarlarkFiles(files: MutableList<net.starlark.java.syntax.StarlarkFile>): com.google.common.collect.ImmutableList<com.google.devtools.build.lib.util.Pair<String?, net.starlark.java.syntax.Location?>?> {
            val loads: com.google.common.collect.ImmutableList.Builder<com.google.devtools.build.lib.util.Pair<String?, net.starlark.java.syntax.Location?>?> =
                com.google.common.collect.ImmutableList.builder<com.google.devtools.build.lib.util.Pair<String?, net.starlark.java.syntax.Location?>?>()
            for (file in files) {
                for (stmt in file.getStatements()) {
                    if (stmt is net.starlark.java.syntax.LoadStatement) {
                        val module: net.starlark.java.syntax.StringLiteral = stmt.getImport()
                        loads.add(
                            com.google.devtools.build.lib.util.Pair.of<String?, net.starlark.java.syntax.Location?>(
                                module.getValue(),
                                module.getStartLocation()
                            )
                        )
                    }
                }
            }
            return loads.build()
        }

        /**
         * Computes the BzlLoadValue for all given .bzl load keys using ordinary Skyframe evaluation,
         * returning `null` if Skyframe deps were missing and have been requested. `programLoads` provides the locations of the load statements in source order, for error
         * reporting.
         */
        @Throws(BzlLoadFailedException::class, java.lang.InterruptedException::class)
        private fun computeBzlLoadsWithSkyframe(
            env: SkyFunction.Environment,
            keys: MutableList<BzlLoadValue.Key?>,
            programLoads: MutableList<com.google.devtools.build.lib.util.Pair<String?, net.starlark.java.syntax.Location?>>
        ): MutableList<BzlLoadValue>? {
            val bzlLoads: MutableList<BzlLoadValue?> =
                com.google.common.collect.Lists.newArrayListWithExpectedSize<BzlLoadValue?>(keys.size())
            val values: SkyframeLookupResult = env.getValuesAndExceptions(keys)
            // Process loads (and report first error) in source order.
            for (i in keys.indices) {
                try {
                    bzlLoads.add(
                        values.getOrThrow<E?>(
                            keys.get(i),
                            BzlLoadFailedException::class.java
                        ) as BzlLoadValue?
                    )
                } catch (ex: BzlLoadFailedException) {
                    throw whileLoadingDep(programLoads.get(i).second, ex)
                }
            }
            return if (env.valuesMissing()) null else bzlLoads
        }

        /**
         * Checks that all (directly) requested loads are visible to the requesting file's package.
         * 
         * 
         * Each load that is not visible is reported as an error on the event handler. If there is at
         * least one error, [BzlLoadFailedException] is thrown.
         * 
         * 
         * The requesting file may be a .bzl file or another Starlark file (BUILD, WORKSPACE, etc.).
         * `requestingPackage` is its logical containing package used for visibility validation,
         * while `requestingFileDescription` is a piece of text for error messages, e.g. "module
         * foo.bzl".
         * 
         * 
         * `loadValues`, `loadKeys`, and `programLoads` are all ordered corresponding
         * to the load statements of the requesting bzl.
         */
        // TODO(brandjon): It'd be nice to pass in a single Label argument that unifies requestingPackage
        // and requestingFileDescription. But some callers of PackageFunction#loadBzlModules don't have
        // such a label handy. Ex: Workspace logic has multiple possible sources of workspace file
        // content.
        @Throws(BzlLoadFailedException::class)
        fun checkLoadVisibilities(
            requestingPackage: PackageIdentifier,
            requestingFileDescription: String?,
            loadValues: MutableList<BzlLoadValue>,
            loadKeys: MutableList<BzlLoadValue.Key?>,
            programLoads: MutableList<com.google.devtools.build.lib.util.Pair<String?, net.starlark.java.syntax.Location?>>,
            demoteErrorsToWarnings: Boolean,
            isUnderExperimental: java.util.function.Predicate<PackageIdentifier?>,
            isUnderPrototype: java.util.function.Predicate<PackageIdentifier?>,
            handler: com.google.devtools.build.lib.events.EventHandler
        ) {
            if (isUnderExperimental.test(requestingPackage)) {
                // Experimental code is exempted from load visibility.
                return
            }
            val requestingIsPrototype: Boolean = isUnderPrototype.test(requestingPackage)

            var foundViolation = false
            for (i in loadValues.indices) {
                val loadLabel: Label = loadKeys.get(i).label
                val loadPackage: PackageIdentifier? = loadLabel.getPackageIdentifier()
                if (requestingIsPrototype && !isUnderPrototype.test(loadPackage)) {
                    // Prototypes can always load from normal packages; there's no load-visibility equivalent
                    // flag for --check_visibility_for_prototypes. But load visibility is still enforced between
                    // two prototypes packages (possibly demoted to a warning, below).
                    continue
                }

                val loadVisibility: BzlVisibility = loadValues.get(i).getBzlVisibility()
                if (!(requestingPackage.equals(loadPackage)
                            || loadVisibility.allowsPackage(requestingPackage))
                ) {
                    val loc: net.starlark.java.syntax.Location? = programLoads.get(i).second
                    var msg: String? =
                        java.lang.String.format( // TODO(brandjon): Consider whether we should try to report error messages (here
                            // and elsewhere) using the literal text of the load() rather than the (already
                            // repo-remapped) label.
                            "Starlark file %s is not visible for loading from package %s. Check the"
                                    + " file's `visibility()` declaration.",
                            loadLabel, requestingPackage.getCanonicalForm()
                        )
                    if (demoteErrorsToWarnings) {
                        msg += " Continuing because --nocheck_bzl_visibility is active"
                        handler.handle(com.google.devtools.build.lib.events.Event.warn(loc, msg))
                    } else {
                        handler.handle(com.google.devtools.build.lib.events.Event.error(loc, msg))
                    }
                    foundViolation = true
                }
            }
            if (foundViolation && !demoteErrorsToWarnings) {
                throw visibilityViolation(requestingFileDescription)
            }
        }

        /** Executes the compiled .bzl file defining the module to be loaded.  */
        @Throws(BzlLoadFailedException::class, java.lang.InterruptedException::class)
        private fun executeBzlFile(
            prog: net.starlark.java.syntax.Program,
            key: BzlLoadValue.Key,
            module: net.starlark.java.eval.Module,
            loadedModules: MutableMap<String?, net.starlark.java.eval.Module?>,
            context: BzlInitThreadContext,
            starlarkSemantics: net.starlark.java.eval.StarlarkSemantics?,
            skyframeEventHandler: ExtendedEventHandler,
            repoMappingRecorder: Label.RepoMappingRecorder?
        ) {
            val label: Label = key.label
            if (prog.isMutationFreeAtTopLevel())
                net.starlark.java.eval.Mutability.IMMUTABLE
            else
                net.starlark.java.eval.Mutability.create("loading", label).use { mu ->
                    val thread: net.starlark.java.eval.StarlarkThread =
                        net.starlark.java.eval.StarlarkThread.create(
                            mu,
                            starlarkSemantics,  /* contextDescription= */
                            "",
                            net.starlark.java.eval.SymbolGenerator.create<Any?>(key)
                        )
                    thread.setLoader(net.starlark.java.eval.StarlarkThread.Loader { key: String? ->
                        loadedModules.get(
                            key
                        )
                    })
                    // This is needed so that any calls to `Label()` will have its used repo mapping entries
                    // recorded. See #20721 for more details.
                    thread.setThreadLocal<Label.RepoMappingRecorder?>(
                        Label.RepoMappingRecorder::class.java,
                        repoMappingRecorder
                    )

                    // Wrap the skyframe event handler to listen for starlark errors.
                    val sawStarlarkError: AtomicBoolean = AtomicBoolean(false)
                    val starlarkEventHandler: com.google.devtools.build.lib.events.EventHandler =
                        com.google.devtools.build.lib.events.EventHandler { event: com.google.devtools.build.lib.events.Event? ->
                            if (event.getKind() == com.google.devtools.build.lib.events.EventKind.ERROR) {
                                sawStarlarkError.set(true)
                            }
                            skyframeEventHandler.handle(event)
                        }
                    thread.setPrintHandler(
                        com.google.devtools.build.lib.events.Event.makeDebugPrintHandler(
                            starlarkEventHandler
                        )
                    )
                    context.storeInThread(thread)

                    execAndExport(prog, label, starlarkEventHandler, module, thread)
                    if (sawStarlarkError.get()) {
                        throw executionFailed(label)
                    }
                }
        }

        // Precondition: thread has a valid transitiveDigest.
        // TODO(adonovan): executeBzlFile would make a better public API than this function.
        @Throws(java.lang.InterruptedException::class)
        fun execAndExport(
            prog: net.starlark.java.syntax.Program,
            label: Label?,
            handler: com.google.devtools.build.lib.events.EventHandler,
            module: net.starlark.java.eval.Module,
            thread: net.starlark.java.eval.StarlarkThread
        ) {
            // Intercept execution after every assignment at top level
            // and "export" any newly assigned exportable globals.
            // TODO(adonovan): change the semantics; see b/65374671.

            thread.setPostAssignHook(
                net.starlark.java.eval.StarlarkThread.PostAssignHook { name: String?, nameStartLocation: net.starlark.java.syntax.Location?, value: Any? ->
                    if (value is StarlarkExportable) {
                        if (!value.isExported()) {
                            value.export(handler, label, name, nameStartLocation)
                        }
                    }
                })

            try {
                net.starlark.java.eval.Starlark.execFileProgram(prog, module, thread)
            } catch (ex: net.starlark.java.eval.EvalException) {
                handler.handle(com.google.devtools.build.lib.events.Event.error(null, ex.getMessageWithStack()))
            }
        }

        private fun whileLoadingDep(
            loc: net.starlark.java.syntax.Location?, cause: BzlLoadFailedException
        ): BzlLoadFailedException {
            // Don't chain exception cause, just incorporate the message with a prefix.
            // TODO(bazel-team): This exception should hold a Location of the requesting file's load
            // statement, and code that catches it should use the location in the Event they create.
            return BzlLoadFailedException(
                "at " + loc + ": " + cause.getMessage(), cause.getDetailedExitCode()
            )
        }

        fun typingFailed(label: Label): BzlLoadFailedException {
            return BzlLoadFailedException(
                java.lang.String.format(
                    "initialization of module '%s'%s failed",  // TODO(brandjon): This error message drops the repo part of the label.
                    label.toPathFragment(),
                    if (StarlarkBuiltinsValue.Companion.isBuiltinsRepo(label.getRepository())) " (internal)" else ""
                ),
                Code.TYPING_ERROR
            )
        }

        fun executionFailed(label: Label): BzlLoadFailedException {
            return BzlLoadFailedException(
                java.lang.String.format(
                    "initialization of module '%s'%s failed",  // TODO(brandjon): This error message drops the repo part of the label.
                    label.toPathFragment(),
                    if (StarlarkBuiltinsValue.Companion.isBuiltinsRepo(label.getRepository())) " (internal)" else ""
                ),
                Code.EVAL_ERROR
            )
        }

        fun errorFindingContainingPackage(file: PathFragment?, cause: java.lang.Exception): BzlLoadFailedException {
            val errorMessage: String? =
                java.lang.String.format(
                    "Encountered error while reading extension file '%s': %s", file, cause.getMessage()
                )
            val detailedExitCode: DetailedExitCode? =
                if (cause is DetailedException)
                    cause.detailedExitCode
                else
                    BzlLoadFailedException.createDetailedExitCode(
                        errorMessage, Code.CONTAINING_PACKAGE_NOT_FOUND
                    )
            return BzlLoadFailedException(errorMessage, detailedExitCode, cause, Transience.PERSISTENT)
        }

        fun errorReadingBzl(
            file: PathFragment?, cause: BzlCompileFunction.FailedIOException
        ): BzlLoadFailedException {
            val errorMessage: String? =
                java.lang.String.format(
                    "Encountered error while reading extension file '%s': %s", file, cause.getMessage()
                )

            if (cause.getCause() is DetailedIOException) {
                return BzlLoadFailedException(
                    errorMessage,
                    detailedException.getDetailedExitCode(),
                    detailedException,
                    detailedException.getTransience()
                )
            }

            return BzlLoadFailedException(errorMessage, Code.IO_ERROR, cause, cause.getTransience())
        }

        fun noBuildFile(file: Label?, reason: String?): BzlLoadFailedException {
            if (reason != null) {
                return BzlLoadFailedException(
                    java.lang.String.format("Unable to find package for %s: %s.", file, reason),
                    Code.PACKAGE_NOT_FOUND
                )
            }
            return BzlLoadFailedException(
                java.lang.String.format(
                    ("Every .bzl file must have a corresponding package, but '%s' does not have one."
                            + " Please create a BUILD file in the same or any parent directory. Note that"
                            + " this BUILD file does not need to do anything except exist."),
                    file
                ),
                Code.PACKAGE_NOT_FOUND
            )
        }

        fun labelCrossesPackageBoundary(
            label: Label?, containingPackageLookupValue: ContainingPackageLookupValue
        ): BzlLoadFailedException {
            return BzlLoadFailedException(
                ContainingPackageLookupValue.getErrorMessageForLabelCrossingPackageBoundary( // We don't actually know the proper Root to pass in here (since we don't e.g. know
                    // the root of the bzl/BUILD file that is trying to load 'label'). Therefore we just
                    // pass in the Root of the containing package in order to still get a useful error
                    // message for the user.
                    containingPackageLookupValue.containingPackageRoot,
                    label,
                    containingPackageLookupValue
                ),
                Code.LABEL_CROSSES_PACKAGE_BOUNDARY
            )
        }

        fun builtinsFailed(file: Label?, cause: BuiltinsFailedException): BzlLoadFailedException {
            return BzlLoadFailedException(
                java.lang.String.format(
                    "Internal error while loading Starlark builtins for %s: %s", file, cause.getMessage()
                ),
                Code.BUILTINS_ERROR,
                cause,
                cause.getTransience()
            )
        }

        /**
         * Returns an exception for load visibility violations.
         * 
         * 
         * `fileDescription` is a string like `"module //pkg:foo.bzl"` or `"file //pkg:BUILD"`.
         */
        fun visibilityViolation(fileDescription: String?): BzlLoadFailedException {
            return BzlLoadFailedException(
                java.lang.String.format("%s contains .bzl load visibility violations", fileDescription),
                Code.VISIBILITY_ERROR
            )
        }
    }
}
