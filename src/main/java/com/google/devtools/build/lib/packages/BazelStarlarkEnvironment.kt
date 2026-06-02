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
package com.google.devtools.build.lib.packages

import com.google.devtools.build.lib.packages.BuiltinsInternalModule
import com.google.devtools.build.lib.packages.StarlarkGlobals
import com.google.devtools.build.lib.packages.StructProvider
import java.util.HashMap
import java.util.LinkedHashMap
import java.util.SequencedMap

// TODO(adonovan): move skyframe.PackageFunction into lib.packages so we needn't expose this and
// the other env-building functions.
/**
 * This class encapsulates knowledge of how to set up the Starlark environment for BUILD, MODULE,
 * and bzl file evaluation, including the top-level predeclared symbols, the `native` module,
 * and the special environment for `@_builtins` bzl evaluation.
 * 
 * 
 * The set of available symbols is determined by
 * 
 * 
 *  1. Gathering a fixed set of top-level symbols that are present in all versions of Bazel. This
 * is handled by [StarlarkGlobals].
 *  1. Gathering additional toplevels and rules registered on the [       ].
 *  1. Applying builtins injection (see [StarlarkBuiltinsFunction]), if applicable.
 * 
 * 
 * 
 * The end result of (1) and (2) is constant for any given Bazel binary and is cached by an
 * instance of this class upon construction. The final environment, which takes into account
 * builtins injection, is obtained by calling methods on this class during Skyframe evaluation; the
 * result is cached in [StarlarkBuiltinsValue].
 * 
 * 
 * There is an exception where this class is not the final word on the environment: If a prelude
 * file is in use, its bindings are added to the ones this class specifies for BUILD files. This
 * happens in [PackageFunction].
 */
class BazelStarlarkEnvironment(
    starlarkGlobals: StarlarkGlobals,
    bazelVersion: String?,
    ruleFunctions: com.google.common.collect.ImmutableMap<String?, *>,
    registeredBuildFileToplevels: com.google.common.collect.ImmutableMap<String?, Any?>,
    registeredBzlToplevels: com.google.common.collect.ImmutableMap<String?, Any?>,
    builtinsInternals: com.google.common.collect.ImmutableMap<String?, Any?>?
) {
    // TODO(#11954): Eventually the BUILD and MODULE bzl dialects should converge. Right now they
    // only differ on the "native" object.
    // All of the environments stored in these fields exclude the symbols in {@link
    // Starlark#UNIVERSE}, which the interpreter adds automatically.
    // Constructor param, used in this class but also re-exported to clients.
    private val starlarkGlobals: StarlarkGlobals?

    // The following fields correspond to the constructor params of the same name. These include only
    // the params that are needed by injection. See the constructor for javadoc.
    private val ruleFunctions: com.google.common.collect.ImmutableMap<String?, *>
    private val registeredBzlToplevels: com.google.common.collect.ImmutableMap<String?, Any?>

    /**
     * The top-level predeclared symbols, excluding `native`, for a .bzl file (regardless of who
     * loads it), before injection.
     */
    private val bzlToplevelsWithoutNative: com.google.common.collect.ImmutableMap<String?, Any?>

    /** The `native` module fields for a BUILD-loaded bzl module, before builtins injection.  */
    private val uninjectedBuildBzlNativeBindings: com.google.common.collect.ImmutableMap<String?, Any?>

    /**
     * The top-level predeclared symbols (including `native`) for a BUILD-loaded bzl module,
     * before builtins injection.
     */
    private val uninjectedBuildBzlEnv: com.google.common.collect.ImmutableMap<String?, Any?>

    /** The top-level predeclared symbols for BUILD files, before builtins injection and prelude.  */
    private val uninjectedBuildEnv: com.google.common.collect.ImmutableMap<String?, Any?>

    /** The `native` module fields for a MODULE-loaded bzl module, before builtins injection.  */
    private val uninjectedModuleBzlNativeBindings: com.google.common.collect.ImmutableMap<String?, Any?>

    /**
     * The top-level predeclared symbols for a MODULE-loaded bzl module, before builtins injection.
     */
    private val uninjectedModuleBzlEnv: com.google.common.collect.ImmutableMap<String?, Any?>

    /** The top-level predeclared symbols for a bzl module in the `@_builtins` pseudo-repo.  */
    private val builtinsBzlEnv: com.google.common.collect.ImmutableMap<String?, Any?>

    /** The top-level predeclared symbols for a MODULE.bazel file.  */
    private val moduleBazelEnv: com.google.common.collect.ImmutableMap<String?, Any?>?

    /** The top-level predeclared symbols for a REPO.bazel file.  */
    private val repoBazelEnv: com.google.common.collect.ImmutableMap<String?, Any?>?

    /**
     * Constructs a new `BazelStarlarkEnvironment` that will have complete knowledge of the
     * proper Starlark symbols available in each context, with and without injection.
     * 
     * @param ruleFunctions a map from a rule class name (e.g. "java_library") to the (uninjected)
     * Starlark callable that instantiates it
     * @param registeredBuildFileToplevels a map of additional (i.e., registered with the rule class
     * provider) top-level symbols for BUILD files, prior to builtins injection. These symbols are
     * also added to the `native` object. Does not include rules.
     * @param registeredBzlToplevels a map of additional (i.e., registered with the rule class
     * provider) top-level symbols for .bzl files, prior to builtins injection
     * @param builtinsInternals a set of symbols to be made available to `@_builtins` .bzls
     * under the `_builtins.internal` object. These symbols are not exposed to user .bzl
     * code and do not constitute a public or stable API if not exposed through another means.
     */
    init {
        this.starlarkGlobals = starlarkGlobals
        this.ruleFunctions = ruleFunctions
        this.registeredBzlToplevels = registeredBzlToplevels

        this.bzlToplevelsWithoutNative =
            createBzlToplevelsWithoutNative(starlarkGlobals, registeredBzlToplevels)
        // TODO(#11954): Use the same "native" object for both BUILD- and MODULE-loaded .bzls, and
        // just have it be a dynamic error to call the wrong thing at the wrong time. This is a breaking
        // change.
        this.uninjectedBuildBzlNativeBindings =
            createUninjectedBuildBzlNativeBindings(
                starlarkGlobals, ruleFunctions, registeredBuildFileToplevels
            )
        this.uninjectedModuleBzlNativeBindings =
            createUninjectedModuleBzlNativeBindings(starlarkGlobals, ruleFunctions, bazelVersion)

        this.uninjectedBuildBzlEnv =
            createUninjectedBzlEnv(bzlToplevelsWithoutNative, uninjectedBuildBzlNativeBindings)
        this.uninjectedModuleBzlEnv =
            createUninjectedBzlEnv(bzlToplevelsWithoutNative, uninjectedModuleBzlNativeBindings)
        this.builtinsBzlEnv =
            createBuiltinsBzlEnv(
                starlarkGlobals,
                builtinsInternals,
                uninjectedBuildBzlNativeBindings,
                uninjectedBuildBzlEnv
            )
        this.uninjectedBuildEnv =
            createUninjectedBuildEnv(starlarkGlobals, ruleFunctions, registeredBuildFileToplevels)
        this.moduleBazelEnv = starlarkGlobals.getModuleToplevels()
        this.repoBazelEnv = starlarkGlobals.getRepoToplevels()
    }

    /**
     * Returns a [StarlarkGlobals] instance.
     * 
     * 
     * In practice, [StarlarkGlobals] is a singleton, so this accessor is really about
     * retrieving [StarlarkGlobalsImpl.INSTANCE] without requiring a dependency on the
     * lib/analysis/ package.
     */
    fun getStarlarkGlobals(): StarlarkGlobals? {
        return starlarkGlobals
    }

    /**
     * Returns the contents of the "native" object for BUILD-loaded bzls, not accounting for builtins
     * injection.
     */
    fun getUninjectedBuildBzlNativeBindings(): com.google.common.collect.ImmutableMap<String?, Any?> {
        return uninjectedBuildBzlNativeBindings
    }

    /**
     * Returns the contents of the "native" object for MODULE-loaded bzls, not accounting for builtins
     * injection.
     */
    fun getUninjectedModuleBzlNativeBindings(): com.google.common.collect.ImmutableMap<String?, Any?> {
        return uninjectedModuleBzlNativeBindings
    }

    /**
     * Returns the original environment for BUILD-loaded bzl files, not accounting for builtins
     * injection. Excludes symbols in [Starlark.UNIVERSE].
     * 
     * 
     * The post-injection environment may differ from this one by what symbols a name is bound to,
     * but the set of symbols remains the same.
     */
    fun getUninjectedBuildBzlEnv(): com.google.common.collect.ImmutableMap<String?, Any?> {
        return uninjectedBuildBzlEnv
    }

    /**
     * Returns the original environment for BUILD files, not accounting for builtins injection or
     * application of the prelude. Excludes symbols in [Starlark.UNIVERSE].
     * 
     * 
     * Applying builtins injection may update name bindings, but not add or remove them. I.e. some
     * names may refer to different symbols but the static set of names remains the same. Applying the
     * prelude file may update and add name bindings but not remove them.
     */
    fun getUninjectedBuildEnv(): com.google.common.collect.ImmutableMap<String?, Any?> {
        return uninjectedBuildEnv
    }

    /**
     * Returns the original environment for MODULE-loaded bzl files, not accounting for builtins
     * injection. Excludes symbols in [Starlark.UNIVERSE].
     * 
     * 
     * The post-injection environment may differ from this one by what symbols a name is bound to,
     * but the set of symbols remains the same.
     */
    fun getUninjectedModuleBzlEnv(): com.google.common.collect.ImmutableMap<String?, Any?> {
        return uninjectedModuleBzlEnv
    }

    /**
     * Returns the environment for bzl files in the `@_builtins` pseudo-repository. Excludes
     * symbols in [Starlark.UNIVERSE].
     */
    fun getBuiltinsBzlEnv(): com.google.common.collect.ImmutableMap<String?, Any?> {
        return builtinsBzlEnv
    }

    /** Returns the environment for MODULE.bazel files.  */
    fun getModuleBazelEnv(): com.google.common.collect.ImmutableMap<String?, Any?>? {
        return moduleBazelEnv
    }

    /** Returns the environment for REPO.bazel files.  */
    fun getRepoBazelEnv(): com.google.common.collect.ImmutableMap<String?, Any?>? {
        return repoBazelEnv
    }

    /**
     * Constructs an environment for a BUILD-loaded bzl file based on the default environment, the
     * maps corresponding to the `exported_toplevels` and `exported_rules` dicts, and the
     * value of `--experimental_builtins_injection_override`.
     * 
     * 
     * Injected symbols must override an existing symbol of that name. Furthermore, the overridden
     * symbol must be one that was registered on the rule class provider (e.g., `CcInfo` or
     * `cc_library`), not a fixed symbol that's always available (e.g., `provider` or
     * `glob`). Throws InjectionException if these conditions are not met.
     * 
     * 
     * Whether or not injection actually occurs for a given map key depends on its prefix (if any)
     * and the prefix of its appearance (if it appears at all) in the override list; see the
     * documentation for `--experimental_builtins_injection_override`. Non-injected symbols must
     * still obey the above constraints.
     * 
     * @see com.google.devtools.build.lib.skyframe.StarlarkBuiltinsFunction
     */
    @Throws(InjectionException::class)
    fun createBuildBzlEnvUsingInjection(
        exportedToplevels: MutableMap<String?, Any?>,
        exportedRules: MutableMap<String?, Any?>,
        overridesList: MutableList<String>
    ): com.google.common.collect.ImmutableMap<String?, Any?> {
        return createBzlEnvUsingInjection(
            exportedToplevels, exportedRules, overridesList, uninjectedBuildBzlNativeBindings
        )
    }

    /**
     * Constructs an environment for a MODULE-loaded bzl file based on the default environment, the
     * maps corresponding to the `exported_toplevels` and `exported_rules` dicts, and the
     * value of `--experimental_builtins_injection_override`.
     * 
     * @see com.google.devtools.build.lib.skyframe.StarlarkBuiltinsFunction
     */
    @Throws(InjectionException::class)
    fun createModuleBzlEnvUsingInjection(
        exportedToplevels: MutableMap<String?, Any?>,
        exportedRules: MutableMap<String?, Any?>,
        overridesList: MutableList<String>
    ): com.google.common.collect.ImmutableMap<String?, Any?> {
        return createBzlEnvUsingInjection(
            exportedToplevels, exportedRules, overridesList, uninjectedModuleBzlNativeBindings
        )
    }

    @Throws(InjectionException::class)
    private fun createBzlEnvUsingInjection(
        exportedToplevels: MutableMap<String?, Any?>,
        exportedRules: MutableMap<String?, Any?>,
        overridesList: MutableList<String>,
        nativeBase: MutableMap<String?, Any?>?
    ): com.google.common.collect.ImmutableMap<String?, Any?> {
        val overridesMap = parseInjectionOverridesList(overridesList)

        val env: SequencedMap<String?, Any?> = LinkedHashMap<String?, Any?>(bzlToplevelsWithoutNative)

        // Determine "native" bindings.
        // TODO(#11954): See above comment in createUninjectedBuildBzlEnv.
        val nativeBindings: SequencedMap<String?, Any?> = LinkedHashMap<String?, Any?>(nativeBase)
        for (entry in exportedRules.entrySet()) {
            val key: String = entry.getKey()
            val name = getKeySuffix(key)
            validateSymbolIsInjectable(name, nativeBindings.keySet(), ruleFunctions.keySet(), "rule")
            if (injectionApplies(key, overridesMap)) {
                nativeBindings.put(name, entry.getValue())
            }
        }
        env.put("native", createNativeModule(nativeBindings))

        // Determine top-level symbols.
        for (entry in exportedToplevels.entrySet()) {
            val key: String = entry.getKey()
            val name = getKeySuffix(key)
            validateSymbolIsInjectable(
                name,
                com.google.common.collect.Sets.union<String?>(
                    env.keySet(),
                    net.starlark.java.eval.Starlark.UNIVERSE.keySet()
                ),
                registeredBzlToplevels.keySet(),
                "top-level symbol"
            )
            if (injectionApplies(key, overridesMap)) {
                env.put(name, entry.getValue())
            }
        }

        return com.google.common.collect.ImmutableMap.copyOf<String?, Any?>(env)
    }

    /**
     * Constructs an environment for a BUILD file based on the default environment, the map
     * corresponding to the `exported_rules` dict, and the value of `--experimental_builtins_injection_override`.
     * 
     * 
     * Injected rule symbols must override an existing native rule of that name. Only rules may be
     * overridden in this manner, not generic built-ins such as `package` or `glob`.
     * Throws InjectionException if these conditions are not met.
     * 
     * 
     * Whether or not injection actually occurs for a given map key depends on its prefix (if any)
     * and the prefix of its appearance (if it appears at all) in the override list; see the
     * documentation for `--experimental_builtins_injection_override`. Non-injected symbols must
     * still obey the above constraints.
     */
    @Throws(InjectionException::class)
    fun createBuildEnvUsingInjection(
        exportedRules: MutableMap<String?, Any?>, overridesList: MutableList<String>
    ): com.google.common.collect.ImmutableMap<String?, Any?> {
        val overridesMap = parseInjectionOverridesList(overridesList)

        val env: SequencedMap<String?, Any?> = LinkedHashMap<String?, Any?>(uninjectedBuildEnv)
        for (entry in exportedRules.entrySet()) {
            val key: String = entry.getKey()
            val name = getKeySuffix(key)
            validateSymbolIsInjectable(
                name,
                com.google.common.collect.Sets.union<String?>(
                    env.keySet(),
                    net.starlark.java.eval.Starlark.UNIVERSE.keySet()
                ),
                ruleFunctions.keySet(),
                "rule"
            )
            if (injectionApplies(key, overridesMap)) {
                env.put(name, entry.getValue())
            }
        }
        return com.google.common.collect.ImmutableMap.copyOf<String?, Any?>(env)
    }

    /** Indicates a problem performing builtins injection.  */
    class InjectionException internal constructor(message: String?) : java.lang.Exception(message)
    companion object {
        private fun createBzlToplevelsWithoutNative(
            starlarkGlobals: StarlarkGlobals, registeredBzlToplevels: MutableMap<String?, Any?>
        ): com.google.common.collect.ImmutableMap<String?, Any?> {
            val env: com.google.common.collect.ImmutableMap.Builder<String?, Any?> =
                com.google.common.collect.ImmutableMap.Builder<String?, Any?>()
            env.putAll(starlarkGlobals.getFixedBzlToplevels())
            env.putAll(registeredBzlToplevels)
            return env.buildOrThrow()
        }

        /**
         * Produces everything that would be in the `native` object for BUILD-loaded bzl files if
         * builtins injection didn't happen.
         */
        private fun createUninjectedBuildBzlNativeBindings(
            starlarkGlobals: StarlarkGlobals,
            ruleFunctions: MutableMap<String?, *>,
            registeredBuildFileToplevels: MutableMap<String?, Any?>
        ): com.google.common.collect.ImmutableMap<String?, Any?> {
            return com.google.common.collect.ImmutableMap.Builder<String?, Any?>()
                .putAll(starlarkGlobals.getFixedBuildFileToplevelsSharedWithNative())
                .putAll(ruleFunctions)
                .putAll(registeredBuildFileToplevels)
                .buildOrThrow()
        }

        /**
         * Produce everything that would be in the `native` object for MODULE-loaded bzl files if
         * builtins injection didn't happen.
         */
        fun createUninjectedModuleBzlNativeBindings(
            starlarkGlobals: StarlarkGlobals, ruleFunctions: MutableMap<String?, *>, bazelVersion: String?
        ): com.google.common.collect.ImmutableMap<String?, Any?> {
            return com.google.common.collect.ImmutableMap.Builder<String?, Any?>()
                .putAll(starlarkGlobals.getFixedBuildFileToplevelsSharedWithNative())
                .putAll(ruleFunctions)
                .put("bazel_version", bazelVersion)
                .buildOrThrow()
        }

        /** Constructs a "native" module object with the given contents.  */
        private fun createNativeModule(bindings: MutableMap<String?, Any?>?): Any {
            return StructProvider.Companion.STRUCT.create(bindings, "no native function or rule '%s'")
        }

        private fun createUninjectedBzlEnv(
            bzlToplevelsWithoutNative: MutableMap<String?, Any?>,
            uninjectedBzlNativeBindings: MutableMap<String?, Any?>?
        ): com.google.common.collect.ImmutableMap<String?, Any?> {
            val env: com.google.common.collect.ImmutableMap.Builder<String?, Any?> =
                com.google.common.collect.ImmutableMap.Builder<String?, Any?>()
            env.putAll(bzlToplevelsWithoutNative)

            // Determine the "native" module.
            env.put("native", createNativeModule(uninjectedBzlNativeBindings))

            return env.buildOrThrow()
        }

        private fun createUninjectedBuildEnv(
            starlarkGlobals: StarlarkGlobals,
            ruleFunctions: MutableMap<String?, *>,
            registeredBuildFileToplevels: MutableMap<String?, Any?>
        ): com.google.common.collect.ImmutableMap<String?, Any?> {
            val env: com.google.common.collect.ImmutableMap.Builder<String?, Any?> =
                com.google.common.collect.ImmutableMap.builder<String?, Any?>()
            env.putAll(starlarkGlobals.getFixedBuildFileToplevelsSharedWithNative())
            env.putAll(starlarkGlobals.getFixedBuildFileToplevelsNotInNative())
            env.putAll(ruleFunctions)
            env.putAll(registeredBuildFileToplevels)
            return env.buildOrThrow()
        }

        private fun createBuiltinsBzlEnv(
            starlarkGlobals: StarlarkGlobals,
            builtinsInternals: MutableMap<String?, Any?>?,
            uninjectedBuildBzlNativeBindings: MutableMap<String?, Any?>?,
            uninjectedBuildBzlEnv: MutableMap<String?, Any?>
        ): com.google.common.collect.ImmutableMap<String?, Any?> {
            val env: MutableMap<String?, Any?> = HashMap<String?, Any?>(starlarkGlobals.getFixedBzlToplevels())

            // For _builtins.toplevel, replace all GuardedValues with the underlying value;
            // StarlarkSemantics flags do not affect @_builtins.
            //
            // We do this because otherwise we'd need to differentiate the _builtins.toplevel object (and
            // therefore the @_builtins environment) based on StarlarkSemantics. That seems unnecessary.
            // Instead we trust @_builtins to not misuse flag-guarded features, same as native code.
            //
            // If foo is flag-guarded (either experimental or incompatible), it is unconditionally visible
            // as _builtins.toplevel.foo. It is legal to list it in exported_toplevels unconditionally, but
            // the flag still controls whether the symbol is actually visible to user code.
            val unwrappedBuildBzlSymbols: MutableMap<String?, Any?> = HashMap<String?, Any?>()
            for (entry in uninjectedBuildBzlEnv.entrySet()) {
                var symbol: Any? = entry.getValue()
                if (symbol is net.starlark.java.eval.GuardedValue) {
                    symbol = (symbol as net.starlark.java.eval.GuardedValue).getObject()
                }
                unwrappedBuildBzlSymbols.put(entry.getKey(), symbol)
            }

            val builtinsModule: Any =
                BuiltinsInternalModule(
                    createNativeModule(uninjectedBuildBzlNativeBindings),  // createNativeModule() is good enough for the "toplevel" and "internal" objects too.
                    createNativeModule(unwrappedBuildBzlSymbols),
                    createNativeModule(builtinsInternals)
                )
            val conflictingValue = env.put("_builtins", builtinsModule)
            com.google.common.base.Preconditions.checkState(
                conflictingValue == null, "'_builtins' name is reserved for builtins injection"
            )

            return com.google.common.collect.ImmutableMap.copyOf<String?, Any?>(env)
        }

        /**
         * Throws [InjectionException] with an appropriate error message if the given `symbol`
         * is not in both `existingSymbols` and `injectableSymbols`. `kind` is a string
         * describing the domain of `symbol`.
         */
        @Throws(InjectionException::class)
        private fun validateSymbolIsInjectable(
            symbol: String?, existingSymbols: MutableSet<String?>, injectableSymbols: MutableSet<String?>, kind: String?
        ) {
            if (!existingSymbols.contains(symbol)) {
                throw InjectionException(
                    java.lang.String.format(
                        "Injected %s '%s' must override an existing one by that name", kind, symbol
                    )
                )
            } else if (!injectableSymbols.contains(symbol)) {
                throw InjectionException(
                    java.lang.String.format("Cannot override '%s' with an injected %s", symbol, kind)
                )
            }
        }

        /** Given a string prefixed with + or -, returns that prefix character, or null otherwise.  */
        private fun getKeyPrefix(key: String): Char? {
            if (key.isEmpty()) {
                return null
            }
            val prefix: Char = key.charAt(0)
            if (!(prefix == '+' || prefix == '-')) {
                return null
            }
            return prefix
        }

        /**
         * Given a string prefixed with + or -, returns the remainder of the string, or the whole string
         * otherwise.
         */
        private fun getKeySuffix(key: String): String {
            return if (getKeyPrefix(key) == null) key else key.substring(1)
        }

        /**
         * Given a list of strings representing the +/- prefixed items in `--experimental_builtins_injection_override`, returns a map from each item to a Boolean
         * indicating whether it last appeared with the + suffix (True) or - suffix (False).
         * 
         * @throws InjectionException if an item is not prefixed with either "+" or "-"
         */
        @Throws(InjectionException::class)
        private fun parseInjectionOverridesList(overrides: MutableList<String>): MutableMap<String?, Boolean?> {
            val result: HashMap<String?, Boolean?> = HashMap<String?, Boolean?>()
            for (prefixedItem in overrides) {
                val prefix = getKeyPrefix(prefixedItem)
                if (prefix == null) {
                    throw InjectionException(
                        java.lang.String.format("Invalid injection override item: '%s'", prefixedItem)
                    )
                }
                result.put(prefixedItem.substring(1), prefix == '+')
            }
            return result
        }

        /**
         * Given an exports dict key, and an override map, return whether injection should be applied for
         * that key.
         */
        private fun injectionApplies(key: String, overrides: MutableMap<String?, Boolean?>): Boolean {
            val prefix = getKeyPrefix(key)
            if (prefix == null) {
                // Unprefixed; overrides don't get a say in the matter.
                return true
            }
            val override = overrides.get(key.substring(1))
            if (override == null) {
                return prefix == '+'
            } else {
                return override
            }
        }
    }
}
