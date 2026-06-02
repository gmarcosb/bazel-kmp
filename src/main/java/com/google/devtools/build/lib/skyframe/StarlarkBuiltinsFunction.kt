// Copyright 2020 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.cmdline.Label

// TODO(#11437): Update the design doc to change `@builtins` -> `@_builtins`.
// TODO(#11437): Add support to BzlLoadCycleReporter to pretty-print cycles involving
// @_builtins.
// TODO(#11437): Add tombstone feature: If a native symbol is a tombstone object, this signals to
// StarlarkBuiltinsFunction that the corresponding symbol *must* be defined by @_builtins.
// Furthermore, if exports.bzl also says the symbol is a tombstone, any attempt to use it results
// in failure, as if the symbol doesn't exist at all (or with a user-friendly error message saying
// to migrate by adding a load()). Combine tombstones with reading the current incompatible flags
// within @_builtins for awesomeness.
// TODO(#11437, #11954, #11983): To the extent that BUILD-loaded .bzls and WORKSPACE-loaded .bzls
// have the same environment, builtins injection should apply to both of them, not just to
// BUILD-loaded .bzls.
/**
 * A Skyframe function that evaluates the `@_builtins` pseudo-repository and reports the
 * values exported by [.EXPORTS_ENTRYPOINT]. The `@_builtins` pseudo-repository shares a
 * repo mapping with the `@bazel_tools` repository.
 * 
 * 
 * The process of "builtins injection" refers to evaluating this Skyfunction and applying its
 * result to [BzlLoadFunction]'s computation. See also the [design
 * doc](https://docs.google.com/document/d/1GW7UVo1s9X0cti9OMgT3ga5ozKYUWLPk9k8c4-34rC4):
 * 
 * 
 * This function has a trivial key, so there can only be one value in the build at a time. It has
 * a single dependency on the result of evaluating the exports.bzl file to a [BzlLoadValue].
 * 
 * 
 * This function supports a special "inlining" mode, similar to [BzlLoadFunction] (see that
 * class's javadoc and code comments). Whenever we inline [BzlLoadFunction] we also inline
 * [StarlarkBuiltinsFunction] (and [StarlarkBuiltinsFunction]'s calls to [ ] are then themselves inlined!). Similar to [BzlLoadFunction]'s inlining, we
 * cache the result of this computation, and this caching is managed by [ ]. But since there's only a single [ ] node and we don't need to worry about that node's value changing at future
 * invocations or subsequent versions (see [InlineCacheManager.reset] for why), our caching
 * strategy is much simpler and we don't need to bother inlining deps of the Skyframe subgraph.
 */
class StarlarkBuiltinsFunction(bazelStarlarkEnvironment: BazelStarlarkEnvironment) : SkyFunction {
    // Used to obtain the injected environment.
    private val bazelStarlarkEnvironment: BazelStarlarkEnvironment

    init {
        this.bazelStarlarkEnvironment = bazelStarlarkEnvironment
    }

    @Throws(StarlarkBuiltinsFunctionException::class, java.lang.InterruptedException::class)
    override fun compute(skyKey: SkyKey?, env: SkyFunction.Environment): SkyValue? {
        try {
            return computeInternal(
                env, bazelStarlarkEnvironment,  /* inliningState= */null,  /* bzlLoadFunction= */null
            )
        } catch (e: BuiltinsFailedException) {
            throw StarlarkBuiltinsFunctionException(e)
        }
    }

    /**
     * An exception that occurs while trying to determine the injected builtins.
     * 
     * 
     * This exception type typically wraps a [BzlLoadFailedException] and is wrapped by a
     * [BzlLoadFailedException] in turn.
     */
    internal class BuiltinsFailedException private constructor(
        errorMessage: String?,
        cause: java.lang.Exception?,
        transience: Transience?
    ) : java.lang.Exception(errorMessage, cause) {
        private val transience: Transience?

        init {
            this.transience = transience
        }

        fun getTransience(): Transience? {
            return transience
        }

        companion object {
            fun errorEvaluatingBuiltinsBzls(cause: BzlLoadFailedException): BuiltinsFailedException {
                return errorEvaluatingBuiltinsBzls(cause, cause.getTransience())
            }

            fun errorEvaluatingBuiltinsBzls(
                cause: java.lang.Exception, transience: Transience?
            ): BuiltinsFailedException {
                return BuiltinsFailedException(
                    java.lang.String.format("Failed to load builtins sources: %s", cause.getMessage()),
                    cause,
                    transience
                )
            }

            fun errorApplyingExports(cause: java.lang.Exception): BuiltinsFailedException {
                return BuiltinsFailedException(
                    java.lang.String.format("Failed to apply declared builtins: %s", cause.getMessage()),
                    cause,
                    Transience.PERSISTENT
                )
            }
        }
    }

    /** The exception type thrown by [StarlarkBuiltinsFunction].  */
    internal class StarlarkBuiltinsFunctionException private constructor(cause: BuiltinsFailedException) :
        SkyFunctionException(cause, cause.transience)

    companion object {
        /**
         * The label where `@_builtins` symbols are exported from. (Note that this is never
         * conflated with an actual repository named "`@_builtins`" because 1) it is only ever
         * accessed through a special SkyKey, and 2) we disallow the user from defining a repo named
         * `@_builtins` to avoid confusion.)
         */
        @kotlin.jvm.JvmField
        val EXPORTS_ENTRYPOINT: Label? = Label.parseCanonicalUnchecked("@_builtins//:exports.bzl") // unused

        /**
         * Key for loading exports.bzl. Note that `keyForBuiltins` (as opposed to `keyForBuild`) ensures we can resolve `@_builtins`, which is otherwise inaccessible. It
         * also prevents us from cyclically requesting StarlarkBuiltinsFunction again to evaluate
         * exports.bzl.
         */
        @kotlin.jvm.JvmField
        val EXPORTS_ENTRYPOINT_KEY: BzlLoadValue.Key? = BzlLoadValue.keyForBuiltins(EXPORTS_ENTRYPOINT)

        /**
         * Computes this Skyfunction under inlining of [BzlLoadFunction], forwarding the given
         * inlining state.
         * 
         * 
         * The given Skyframe environment must be a [RecordingSkyFunctionEnvironment]. It is
         * unwrapped before calling [BzlLoadFunction]'s inlining code path.
         * 
         * 
         * Returns null on Skyframe restart or error.
         */
        @Throws(BuiltinsFailedException::class, java.lang.InterruptedException::class)
        fun computeInline(
            key: com.google.devtools.build.lib.skyframe.StarlarkBuiltinsValue.Key?,
            inliningState: InliningState,
            bazelStarlarkEnvironment: BazelStarlarkEnvironment,
            bzlLoadFunction: BzlLoadFunction
        ): StarlarkBuiltinsValue? {
            com.google.common.base.Preconditions.checkNotNull<InlineCacheManager?>(bzlLoadFunction.inlineCacheManager)
            val cachedBuiltins: StarlarkBuiltinsValue? = bzlLoadFunction.inlineCacheManager.builtinsRef.get()
            if (cachedBuiltins != null) {
                // See the comment in InlineCacheManager#reset for why it's sound to not inline deps of the
                // entire subgraph here.
                return cachedBuiltins
            }

            // See BzlLoadFunction#computeInline and BzlLoadFunction.InliningState for an explanation of the
            // inlining mechanism and its invariants. For our purposes, the Skyframe environment to use
            // comes from inliningState.
            val computedBuiltins: StarlarkBuiltinsValue? =
                computeInternal(
                    inliningState.getEnvironment(),
                    bazelStarlarkEnvironment,
                    inliningState,
                    bzlLoadFunction
                )
            if (computedBuiltins == null) {
                return null
            }
            // There's a benign race where multiple threads may try to compute-and-cache the single builtins
            // value. Ensure the value computed by winner of that race gets used by everyone.
            bzlLoadFunction.inlineCacheManager.builtinsRef.compareAndSet(null, computedBuiltins)
            return bzlLoadFunction.inlineCacheManager.builtinsRef.get()
        }

        // bzlLoadFunction and inliningState are non-null iff using inlining code path.
        @Throws(BuiltinsFailedException::class, java.lang.InterruptedException::class)
        private fun computeInternal(
            env: SkyFunction.Environment,
            bazelStarlarkEnvironment: BazelStarlarkEnvironment,
            inliningState: InliningState?,
            bzlLoadFunction: BzlLoadFunction?
        ): StarlarkBuiltinsValue? {
            val starlarkSemantics: net.starlark.java.eval.StarlarkSemantics? =
                PrecomputedValue.STARLARK_SEMANTICS.get(env)
            if (starlarkSemantics == null) {
                return null
            }
            // Return the empty value if builtins injection is disabled.
            if (starlarkSemantics.get<String?>(BuildLanguageOptions.EXPERIMENTAL_BUILTINS_BZL_PATH).isEmpty()) {
                return StarlarkBuiltinsValue.Companion.createEmpty(starlarkSemantics)
            }
            // Load exports.bzl. If we were requested using inlining, make sure to inline the call back into
            // BzlLoadFunction.
            val exportsValue: BzlLoadValue?
            try {
                if (inliningState == null) {
                    exportsValue =
                        env.getValueOrThrow<E?>(
                            EXPORTS_ENTRYPOINT_KEY,
                            BzlLoadFailedException::class.java
                        ) as BzlLoadValue?
                } else {
                    exportsValue = bzlLoadFunction.computeInline(EXPORTS_ENTRYPOINT_KEY, inliningState)
                }
            } catch (ex: BzlLoadFailedException) {
                throw BuiltinsFailedException.Companion.errorEvaluatingBuiltinsBzls(ex)
            }

            if (env.valuesMissing()) {
                return null
            }

            // Compute digest of exports.bzl
            val transitiveDigest: ByteArray? = exportsValue.transitiveDigest

            // Apply declarations of exports.bzl to the native predeclared symbols.
            val module: net.starlark.java.eval.Module = exportsValue.getModule()
            try {
                val exportedToplevels: com.google.common.collect.ImmutableMap<String?, Any?>? =
                    getDict(module, "exported_toplevels")
                val exportedRules: com.google.common.collect.ImmutableMap<String?, Any?>? =
                    getDict(module, "exported_rules")
                val exportedToJava: com.google.common.collect.ImmutableMap<String?, Any?>? =
                    getDict(module, "exported_to_java")
                val predeclaredForBuildBzl: com.google.common.collect.ImmutableMap<String?, Any?>? =
                    bazelStarlarkEnvironment.createBuildBzlEnvUsingInjection(
                        exportedToplevels,
                        exportedRules,
                        starlarkSemantics.get<T?>(BuildLanguageOptions.EXPERIMENTAL_BUILTINS_INJECTION_OVERRIDE)
                    )
                val predeclaredForModuleBzl: com.google.common.collect.ImmutableMap<String?, Any?>? =
                    bazelStarlarkEnvironment.createModuleBzlEnvUsingInjection(
                        exportedToplevels,
                        exportedRules,
                        starlarkSemantics.get<T?>(BuildLanguageOptions.EXPERIMENTAL_BUILTINS_INJECTION_OVERRIDE)
                    )
                val predeclaredForBuild: com.google.common.collect.ImmutableMap<String?, Any?>? =
                    bazelStarlarkEnvironment.createBuildEnvUsingInjection(
                        exportedRules,
                        starlarkSemantics.get<T?>(BuildLanguageOptions.EXPERIMENTAL_BUILTINS_INJECTION_OVERRIDE)
                    )

                return StarlarkBuiltinsValue.Companion.create(
                    predeclaredForBuildBzl,
                    predeclaredForModuleBzl,
                    predeclaredForBuild,
                    exportedToJava,
                    transitiveDigest,
                    starlarkSemantics
                )
            } catch (ex: net.starlark.java.eval.EvalException) {
                throw BuiltinsFailedException.Companion.errorApplyingExports(ex)
            } catch (ex: InjectionException) {
                throw BuiltinsFailedException.Companion.errorApplyingExports(ex)
            }
        }

        /**
         * Attempts to retrieve the string-keyed dict named `dictName` from the given `module`.
         * 
         * @return a copy of the dict mappings on success
         * @throws EvalException if the symbol isn't present or is not a dict whose keys are all strings
         */
        @Throws(net.starlark.java.eval.EvalException::class)
        private fun getDict(
            module: net.starlark.java.eval.Module,
            dictName: String?
        ): com.google.common.collect.ImmutableMap<String?, Any?>? {
            val value: Any? = module.getGlobal(dictName)
            if (value == null) {
                throw net.starlark.java.eval.Starlark.errorf("expected a '%s' dictionary to be defined", dictName)
            }
            return com.google.common.collect.ImmutableMap.copyOf<String?, Any?>(
                net.starlark.java.eval.Dict.cast<String?, Any?>(
                    value,
                    String::class.java,
                    Any::class.java,
                    dictName + " dict"
                )
            )
        }
    }
}
