// Copyright 2018 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.analysis.config

import com.github.benmanes.caffeine.cache.Caffeine
import com.google.devtools.build.lib.analysis.config.StarlarkDefinedConfigTransition
import com.google.devtools.build.lib.analysis.config.transitions.ConfigurationTransition
import com.google.devtools.build.lib.analysis.config.transitions.PatchTransition
import com.google.devtools.build.lib.cmdline.Label.PackageContext
import com.google.devtools.build.lib.cmdline.LabelConstants
import com.google.devtools.build.lib.cmdline.LabelSyntaxException
import com.google.devtools.build.lib.packages.RuleTransitionData
import com.google.devtools.build.lib.packages.StructImpl
import com.google.devtools.build.lib.starlarkbuildapi.config.ConfigurationTransitionApi
import com.google.devtools.build.lib.util.HashCodes
import com.google.devtools.build.lib.vfs.PathFragment
import java.util.HashMap
import java.util.LinkedHashSet

/**
 * Implementation of [ConfigurationTransitionApi].
 * 
 * 
 * Represents a configuration transition across a dependency edge defined in Starlark.
 */
abstract class StarlarkDefinedConfigTransition private constructor(
    inputs: MutableList<String>,
    outputs: MutableList<String>,
    repoMapping: com.google.devtools.build.lib.cmdline.RepositoryMapping?,
    parentLabel: com.google.devtools.build.lib.cmdline.Label,
    location: net.starlark.java.syntax.Location?,
    disallowedOptions: MutableList<String?>
) : ConfigurationTransitionApi {
    /**
     * The two groups of build settings that are relevant for a [ ]
     */
    enum class Settings {
        /** Build settings that are read by a [StarlarkDefinedConfigTransition]  */
        INPUTS,

        /** Build settings that are written by a [StarlarkDefinedConfigTransition]  */
        OUTPUTS,

        /** Build settings that are read and/or written by a [StarlarkDefinedConfigTransition]  */
        INPUTS_AND_OUTPUTS
    }

    private val inputsCanonicalizedToGiven: com.google.common.collect.ImmutableMap<com.google.devtools.build.lib.cmdline.Label?, String?>
    private val outputsCanonicalizedToGiven: com.google.common.collect.ImmutableMap<com.google.devtools.build.lib.cmdline.Label?, String?>
    val parentLabel: com.google.devtools.build.lib.cmdline.Label
    private val location: net.starlark.java.syntax.Location?
    private val packageContext: PackageContext

    // The values in this cache should always be instances of StarlarkTransition, but referencing that
    // here results in a circular dependency.
    @Transient
    private val ruleTransitionCache: com.github.benmanes.caffeine.cache.Cache<RuleTransitionData?, PatchTransition?> =
        Caffeine.newBuilder().weakKeys().build<RuleTransitionData?, PatchTransition?>()

    init {
        this.parentLabel = parentLabel
        this.location = location
        packageContext = com.google.devtools.build.lib.cmdline.Label.PackageContext.of(
            parentLabel.getPackageIdentifier(),
            repoMapping
        )

        this.outputsCanonicalizedToGiven =
            getCanonicalizedSettings(
                repoMapping,
                parentLabel,
                outputs,
                disallowedOptions,
                com.google.devtools.build.lib.analysis.config.StarlarkDefinedConfigTransition.Settings.OUTPUTS
            )
        this.inputsCanonicalizedToGiven =
            getCanonicalizedSettings(
                repoMapping,
                parentLabel,
                inputs,
                disallowedOptions,
                com.google.devtools.build.lib.analysis.config.StarlarkDefinedConfigTransition.Settings.INPUTS
            )
    }

    fun getPackageContext(): PackageContext {
        return packageContext
    }

    /** Is this transition an exec transition?  */
    abstract fun isExecTransition(): Boolean

    /**
     * Returns true if this transition is for analysis testing. If true, then only attributes of rules
     * with `analysis_test=true` may use this transition object.
     */
    abstract fun isForAnalysisTesting(): Boolean

    /**
     * Returns the given input option keys for this transition. Only options contained in this list
     * will be provided in the 'settings' argument given to the transition implementation function.
     */
    fun getInputs(): com.google.common.collect.ImmutableList<String?> {
        return inputsCanonicalizedToGiven.values().asList()
    }

    fun getInputsCanonicalizedToGiven(): com.google.common.collect.ImmutableMap<com.google.devtools.build.lib.cmdline.Label?, String?> {
        return inputsCanonicalizedToGiven
    }

    /**
     * Returns the given output option keys for this transition. The transition implementation
     * function must return a dictionary where the options exactly match the elements of this list.
     */
    fun getOutputs(): com.google.common.collect.ImmutableCollection<String?> {
        return outputsCanonicalizedToGiven.values()
    }

    fun getOutputsCanonicalizedToGiven(): com.google.common.collect.ImmutableMap<com.google.devtools.build.lib.cmdline.Label?, String?> {
        return outputsCanonicalizedToGiven
    }

    /** Returns the location of the Starlark code defining the transition.  */
    fun getLocation(): net.starlark.java.syntax.Location? {
        return location
    }

    /**
     * Returns a cache that can be used to ensure that this [StarlarkDefinedConfigTransition]
     * results in at most one [ ] instance per [ ].
     * 
     * 
     * The cache uses [Caffeine.weakKeys] to permit collection of transition objects when the
     * corresponding [Rule] is collectable. As a consequence, it uses identity comparison for
     * keys, but this is fine since [Rule] does not override [Object.equals].
     * 
     * 
     * Profiling shows that constructing transitions and lazily computing their hash code
     * contributes real CPU cost. For a build where every target applies a transition, this produces
     * observable cost, particularly when the transition produces a noop (in which case the cost is
     * pure overhead of the transition infrastructure).
     * 
     * 
     * Note that the transition instance is different from the transition's use. It's normal best
     * practice to have few or even one transition invoke multiple times over multiple configured
     * targets.
     */
    fun createRuleTransition(
        ruleData: RuleTransitionData?,
        createTransition: java.util.function.Function<RuleTransitionData?, out PatchTransition?>?
    ): PatchTransition? {
        return this.ruleTransitionCache.get(ruleData, createTransition)
    }

    /**
     * Given a map of a subset of the "previous" build settings, returns the changed build settings as
     * a result of applying this transition.
     * 
     * @param previousSettings a map representing the previous build settings
     * @param attributeMapper a map of attributes
     * @param optionInfoMap info about each option's [Option] type
     * @param handler handler for messages
     * @return a map of changed build setting maps; each element of the map represents a different
     * child configuration (split transitions will have multiple elements in this map with keys
     * provided by the transition impl, patch transitions should have a single element keyed by
     * `PATCH_TRANSITION_KEY`). Each build setting map is a map from build setting to target
     * setting value; all other build settings will remain unchanged. Returns null if errors were
     * reported to the handler.
     * @throws InterruptedException if evaluating the transition is interrupted
     */
    @Throws(java.lang.InterruptedException::class)
    abstract fun evaluate(
        previousSettings: MutableMap<String?, Any?>?,
        attributeMapper: StructImpl?,
        optionInfoMap: com.google.common.collect.ImmutableMap<String?, com.google.devtools.build.lib.analysis.config.OptionInfo?>?,
        handler: com.google.devtools.build.lib.events.EventHandler?
    ): com.google.common.collect.ImmutableMap<String?, MutableMap<com.google.devtools.build.lib.cmdline.Label?, Any?>?>?

    private class AnalysisTestTransition(
        changedSettings: MutableMap<String?, Any?>,
        repoMapping: com.google.devtools.build.lib.cmdline.RepositoryMapping?,
        parentLabel: com.google.devtools.build.lib.cmdline.Label,
        location: net.starlark.java.syntax.Location?,
        disallowedOptions: MutableList<String?>
    ) : StarlarkDefinedConfigTransition( /* inputs= */
        com.google.common.collect.ImmutableList.of<String?>(),
        com.google.common.collect.ImmutableList.copyOf<String?>(changedSettings.keySet()),
        repoMapping,
        parentLabel,
        location,
        disallowedOptions
    ) {
        private val changedSettings: MutableMap<String?, Any?>
        private val hashCode: Int

        init {
            this.changedSettings = changedSettings
            this.hashCode = HashCodes.hashObjects(getInputs(), getOutputs(), changedSettings)
        }

        override fun isForAnalysisTesting(): Boolean {
            return true
        }

        override fun isExecTransition(): Boolean {
            return false
        }

        override fun evaluate(
            previousSettings: MutableMap<String?, Any?>?,
            attributeMapper: StructImpl?,
            optionInfoMap: com.google.common.collect.ImmutableMap<String?, com.google.devtools.build.lib.analysis.config.OptionInfo?>?,
            eventHandler: com.google.devtools.build.lib.events.EventHandler?
        ): com.google.common.collect.ImmutableMap<String?, MutableMap<com.google.devtools.build.lib.cmdline.Label?, Any?>?> {
            return com.google.common.collect.ImmutableMap.of<String?, MutableMap<com.google.devtools.build.lib.cmdline.Label?, Any?>?>(
                ConfigurationTransition.PATCH_TRANSITION_KEY,
                com.google.common.collect.Maps.transformValues<com.google.devtools.build.lib.cmdline.Label?, String?, Any?>(
                    getOutputsCanonicalizedToGiven(),
                    com.google.common.base.Function { key: String? -> changedSettings.get(key) })
            )
        }

        override fun repr(
            printer: net.starlark.java.eval.Printer,
            semantics: net.starlark.java.eval.StarlarkSemantics?
        ) {
            printer.append("<analysis_test_transition object>")
        }

        override fun equals(`object`: Any?): Boolean {
            if (`object` === this) {
                return true
            }
            if (`object` is AnalysisTestTransition) {
                return `object`.getInputs() == this.getInputs()
                        && `object`.getOutputs() == this.getOutputs()
                        && `object`.changedSettings == this.changedSettings
            }
            return false
        }

        override fun hashCode(): Int {
            return hashCode
        }
    }

    /** A transition with a user-defined implementation function.  */
    open class RegularTransition internal constructor(
        impl: net.starlark.java.eval.StarlarkCallable,
        inputs: MutableList<String>,
        outputs: MutableList<String>,
        semantics: net.starlark.java.eval.StarlarkSemantics?,
        parentLabel: com.google.devtools.build.lib.cmdline.Label,
        location: net.starlark.java.syntax.Location?,
        repoMapping: com.google.devtools.build.lib.cmdline.RepositoryMapping?,
        disallowedOptions: MutableList<String?>
    ) : StarlarkDefinedConfigTransition(inputs, outputs, repoMapping, parentLabel, location, disallowedOptions) {
        private val impl: net.starlark.java.eval.StarlarkCallable
        private val semantics: net.starlark.java.eval.StarlarkSemantics?
        private val repoMapping: com.google.devtools.build.lib.cmdline.RepositoryMapping?
        private val hashCode: Int

        init {
            this.impl = impl
            this.semantics = semantics
            this.repoMapping = repoMapping
            this.hashCode = HashCodes.hashObjects(getInputs(), getOutputs(), impl)
        }

        override fun isForAnalysisTesting(): Boolean {
            return false
        }

        override fun isExecTransition(): Boolean {
            return false
        }

        /** An exception for validating that a transition is properly constructed  */
        private class UnreadableInputSettingException(
            unreadableSetting: String?,
            unreadableClass: java.lang.Class<*>?
        ) : java.lang.Exception(
            java.lang.String.format(
                "Input build setting %s is of type %s, which is unreadable in Starlark."
                        + " Please submit a feature request.",
                unreadableSetting, unreadableClass
            )
        )

        /**
         * This method evaluates the implementation function of the transition.
         * 
         * 
         * In the case of a [ ], the impl fxn
         * returns a [Dict] of option name strings to option value object.
         * 
         * 
         * In the case of [ ], the impl fxn can
         * return either a [Dict] of String keys to [Dict] values. Or it can return a list
         * of [Dict]s in cases where the consumer doesn't care about differentiating between the
         * splits (i.e. accessing later via `ctx.split_attrs`).
         * 
         * @param previousSettings a map representing the previous build settings
         * @param attrObject the attributes of the rule to which this transition is attached
         * @param optionInfoMap info about each option's [Option] type
         * @param handler handler for messages
         * @return a map of the changed settings. An empty map is shorthand for the transition not
         * changing any settings (`return {} ` is simpler than assigning every output setting
         * to itself). A null return means an error occurred and results are unusable.
         */
        // TODO(bazel-team): integrate dict-of-dicts return type with ctx.split_attr
        @Throws(java.lang.InterruptedException::class)
        override fun evaluate(
            previousSettings: MutableMap<String?, Any?>,
            attrObject: StructImpl?,
            optionInfoMap: com.google.common.collect.ImmutableMap<String?, com.google.devtools.build.lib.analysis.config.OptionInfo?>,
            handler: com.google.devtools.build.lib.events.EventHandler
        ): com.google.common.collect.ImmutableMap<String?, MutableMap<com.google.devtools.build.lib.cmdline.Label?, Any?>?>? {
            // Call the Starlark function.
            val result: Any
            try {
                net.starlark.java.eval.Mutability.create("eval_transition_function").use { mu ->
                    // TODO(brandjon): If the resulting values of Starlark transitions ever evolve to be
                    //  complex Starlark objects like structs as opposed to the ints, strings,
                    //  etc they are today then we need a real symbol generator which is used
                    //  to calculate equality between instances of Starlark objects. A candidate
                    //  for transition instance uniqueness is the Rule and configuration that
                    //  are used as inputs to the configuration.
                    val thread: net.starlark.java.eval.StarlarkThread =
                        net.starlark.java.eval.StarlarkThread.createTransient(mu, semantics)
                    thread.setPrintHandler(com.google.devtools.build.lib.events.Event.makeDebugPrintHandler(handler))
                    val previousSettingsDict: net.starlark.java.eval.Dict<String?, Any?>? =
                        createBuildSettingsDict(previousSettings, optionInfoMap, mu)
                    result = net.starlark.java.eval.Starlark.positionalOnlyCall(
                        thread,
                        impl,
                        previousSettingsDict,
                        attrObject
                    )
                }
            } catch (ex: UnreadableInputSettingException) {
                // TODO(blaze-configurability-team): Ideally, the error would happen (and thus location)
                //   at the transition() call during loading phase. Instead, error happens at the impl
                //  function call during the analysis phase.
                handler.handle(
                    com.google.devtools.build.lib.events.Event.error(
                        impl.getLocation(),
                        java.lang.String.format("before calling %s: %s", impl.getName(), ex.getMessage())
                    )
                )
                return null
            } catch (ex: net.starlark.java.eval.EvalException) {
                handler.handle(com.google.devtools.build.lib.events.Event.error(null, ex.getMessageWithStack()))
                return null
            }

            if (result is net.starlark.java.eval.NoneType) {
                return com.google.common.collect.ImmutableMap.of<String?, MutableMap<com.google.devtools.build.lib.cmdline.Label?, Any?>?>()
            } else if (result is net.starlark.java.eval.Dict<*, *>) {
                if (result.isEmpty()) {
                    return com.google.common.collect.ImmutableMap.of<String?, MutableMap<com.google.devtools.build.lib.cmdline.Label?, Any?>?>()
                }
                try {
                    val dictOfDict: MutableMap<String?, *> =
                        net.starlark.java.eval.Dict.cast<String?, net.starlark.java.eval.Dict<*, *>?>(
                            result,
                            String::class.java,
                            net.starlark.java.eval.Dict::class.java,
                            "dictionary of options dictionaries"
                        )
                    val builder: com.google.common.collect.ImmutableMap.Builder<String?, MutableMap<com.google.devtools.build.lib.cmdline.Label?, Any?>?> =
                        com.google.common.collect.ImmutableMap.builder<String?, MutableMap<com.google.devtools.build.lib.cmdline.Label?, Any?>?>()
                    for (entry in dictOfDict.entrySet()) {
                        val rawDict: MutableMap<String?, Any?> =
                            net.starlark.java.eval.Dict.cast<String?, Any?>(
                                entry.getValue(),
                                String::class.java,
                                Any::class.java,
                                "dictionary of options"
                            )
                        val canonicalizedDict: com.google.common.collect.ImmutableMap<com.google.devtools.build.lib.cmdline.Label?, Any?> =
                            canonicalizeTransitionOutputDict(rawDict, repoMapping, parentLabel, getOutputs())
                        builder.put(entry.getKey(), canonicalizedDict)
                    }
                    return builder.buildOrThrow()
                } catch (ex: ValidationException) {
                    errorf(handler, "invalid result from transition function: %s", ex.getMessage())
                    return null
                } catch (ex: net.starlark.java.eval.EvalException) {
                    // Fall through assuming the Dict#cast call didn't work as this is a single dictionary
                    // not a dictionary of dictionaries.
                }
                try {
                    // Try if this is a patch transition.
                    val rawDict: MutableMap<String?, Any?> =
                        net.starlark.java.eval.Dict.cast<String?, Any?>(
                            result,
                            String::class.java,
                            Any::class.java,
                            "dictionary of options"
                        )
                    val canonicalizedDict: com.google.common.collect.ImmutableMap<com.google.devtools.build.lib.cmdline.Label?, Any?> =
                        canonicalizeTransitionOutputDict(rawDict, repoMapping, parentLabel, getOutputs())
                    return com.google.common.collect.ImmutableMap.of<String?, MutableMap<com.google.devtools.build.lib.cmdline.Label?, Any?>?>(
                        ConfigurationTransition.PATCH_TRANSITION_KEY,
                        canonicalizedDict
                    )
                } catch (ex: net.starlark.java.eval.EvalException) {
                    // TODO(adonovan): explain "want dict<string, any> or dict<string, dict<string, any>>".
                    errorf(handler, "invalid result from transition function: %s", ex.getMessage())
                    return null
                } catch (ex: ValidationException) {
                    errorf(handler, "invalid result from transition function: %s", ex.getMessage())
                    return null
                }
            } else if (result is net.starlark.java.eval.Sequence<*>) {
                if (result.isEmpty()) {
                    return com.google.common.collect.ImmutableMap.of<String?, MutableMap<com.google.devtools.build.lib.cmdline.Label?, Any?>?>()
                }
                val builder: com.google.common.collect.ImmutableMap.Builder<String?, MutableMap<com.google.devtools.build.lib.cmdline.Label?, Any?>?> =
                    com.google.common.collect.ImmutableMap.builder<String?, MutableMap<com.google.devtools.build.lib.cmdline.Label?, Any?>?>()
                try {
                    var i = 0
                    for (entry in net.starlark.java.eval.Sequence.cast<net.starlark.java.eval.Dict<*, *>?>(
                        result,
                        net.starlark.java.eval.Dict::class.java,
                        "dictionary of options dictionaries"
                    )) {
                        // TODO(b/146347033): Document this behavior.
                        val rawDict: MutableMap<String?, Any?> =
                            net.starlark.java.eval.Dict.cast<String?, Any?>(
                                entry,
                                String::class.java,
                                Any::class.java,
                                "dictionary of options"
                            )
                        val canonicalizedDict: com.google.common.collect.ImmutableMap<com.google.devtools.build.lib.cmdline.Label?, Any?> =
                            canonicalizeTransitionOutputDict(rawDict, repoMapping, parentLabel, getOutputs())
                        builder.put(java.lang.Integer.toString(i++), canonicalizedDict)
                    }
                } catch (ex: net.starlark.java.eval.EvalException) {
                    // TODO(adonovan): explain "want sequence of dict<string, any>".
                    errorf(handler, "invalid result from transition function: %s", ex.getMessage())
                    return null
                } catch (ex: ValidationException) {
                    errorf(handler, "invalid result from transition function: %s", ex.getMessage())
                    return null
                }
                return builder.buildOrThrow()
            } else {
                errorf(
                    handler,
                    "transition function returned %s, want dict or list of dicts",
                    net.starlark.java.eval.Starlark.type(result)
                )
                return null
            }
        }

        @com.google.errorprone.annotations.FormatMethod
        private fun errorf(
            handler: com.google.devtools.build.lib.events.EventHandler,
            format: String,
            vararg args: Any?
        ) {
            handler.handle(
                com.google.devtools.build.lib.events.Event.error(
                    impl.getLocation(),
                    java.lang.String.format(format, *args)
                )
            )
        }

        override fun repr(
            printer: net.starlark.java.eval.Printer,
            semantics: net.starlark.java.eval.StarlarkSemantics?
        ) {
            printer.append("<transition object>")
        }

        override fun equals(`object`: Any?): Boolean {
            if (`object` === this) {
                return true
            }
            if (`object` is RegularTransition) {
                return `object`.getInputs() == this.getInputs()
                        && `object`.getOutputs() == this.getOutputs()
                        && `object`.impl == this.impl
            }
            return false
        }

        override fun hashCode(): Int {
            return hashCode
        }

        companion object {
            /**
             * Copy settings into Starlark-readable Dict.
             * 
             * 
             * The returned (outer) Dict will be immutable but all the underlying entries will have
             * mutability given by the entryMu param.
             * 
             * @param settings map os settings to copy over * `optionInfoMap` info about each option's
             * [Option] type
             * @param optionInfoMap info about each option's [Option] type
             * @param entryMu Mutability context to use when copying individual entries
             * @throws UnreadableInputSettingException when entry in build setting is not convertable (using
             * [Starlark.fromJava])
             */
            @Throws(UnreadableInputSettingException::class)
            private fun createBuildSettingsDict(
                settings: MutableMap<String?, Any?>,
                optionInfoMap: com.google.common.collect.ImmutableMap<String?, com.google.devtools.build.lib.analysis.config.OptionInfo?>,
                entryMu: net.starlark.java.eval.Mutability?
            ): net.starlark.java.eval.Dict<String?, Any?>? {
                // Need to convert contained values into Starlark readable values.

                val builder: net.starlark.java.eval.Dict.Builder<String?, Any?> =
                    net.starlark.java.eval.Dict.builder<String?, Any?>()
                for (entry in settings.entrySet()) {
                    try {
                        builder.put(entry.getKey(), net.starlark.java.eval.Starlark.fromJava(entry.getValue(), entryMu))
                    } catch (e: net.starlark.java.eval.Starlark.InvalidStarlarkValueException) {
                        // Starlark#frromJava doesn't know how to read this value. Try again with a special
                        // allowlist of types we know how to make Starlark-compatible. This is not complete. If a
                        // value a) isn't Starlark-convertible and b) not special-cased here, Bazel emits a "can't
                        // process this setting" error.
                        builder.put(
                            entry.getKey(),
                            net.starlark.java.eval.Starlark.fromJava(
                                getTransitionSafeString(entry.getKey(), entry.getValue(), optionInfoMap),
                                entryMu
                            )
                        )
                    }
                }

                // Want the 'outer' build settings dictionary to be immutable
                return builder.buildImmutable()
            }

            /**
             * Converts a Java-native flag value to a Starlark-readable string, or throws an exception if
             * the flag's type can't be represented in Starlark.
             * 
             * 
             * This only kicks in for values [Starlark.fromJava] failed to directly convert. That
             * implies they need extra processing, which is what happens here.
             * 
             * 
             * This is incomplete. It only handles types we explicitly know are Starlark-convertible or
             * that handle [Converter.starlarkConvertible]. Other flags emit a "can't process this
             * setting" error.
             */
            @Throws(UnreadableInputSettingException::class)
            private fun getTransitionSafeString(
                name: String,
                value: Any,
                optionInfoMap: com.google.common.collect.ImmutableMap<String?, com.google.devtools.build.lib.analysis.config.OptionInfo?>
            ): Any? {
                if (value is com.google.devtools.build.lib.util.RegexFilter) {
                    // RegExFilter doesn't serialize to the same value it originally had on the command line.
                    // Call toOriginalString, to do that properly.
                    return com.google.common.base.Verify.verifyNotNull<String?>((value as com.google.devtools.build.lib.util.RegexFilter).toOriginalString())
                }
                if (value is PathFragment) {
                    // Starlark#fromJava doesn't understand this Bazel-specific Java type. But its toString()
                    // method serializes cleanly.
                    return value.toString()
                }
                // See if the option's converter knows how to produce to Starlark values.
                val optionDef: com.google.devtools.common.options.OptionDefinition =
                    optionInfoMap
                        .get(name.substring(LabelConstants.COMMAND_LINE_OPTION_PREFIX.length()))
                        .getDefinition()
                if (!optionDef.getConverter().starlarkConvertible()) {
                    throw UnreadableInputSettingException(name, value.getClass())
                }
                if (optionDef.allowsMultiple()) {
                    // allowMultiple() options are complicated (see the definition of allowMultiple() in
                    // Option.java). They must be typed as a List<T>. Their converters can return either T or
                    // List<T>. In the latter case, the typed value is a concatenation of all the converted
                    // lists.
                    //
                    // Option metadata doesn't include enough information to know which version of the converter
                    // it uses. Also note we can't encode this information in the converter because different
                    // options may use the same converter with or without allowMultiple.
                    //
                    // For lack of direct support, this code infers the right logic.
                    val asList = (value as MutableList<*>)
                    // If this is an empty list, Starlark#fromJava should have handled it.
                    com.google.common.base.Verify.verify(!asList.isEmpty())
                    // The converter matches the option with generics. So we don't actually know how their types
                    // compare at runtime. We know allowMultiple options must be typed List<T>. We assume if the
                    // converter doesn't return a list, it returns a single T. Else it returns a List<T>. This
                    // works as long as the option isn't a List<List<?>>. This verification check confirms that.
                    com.google.common.base.Verify.verify(asList.get(0) !is MutableList<*>)
                    return asList.stream()
                        .map<String?> { o: Any? -> optionDef.getConverter().reverseForStarlark(o) }
                        .collect(com.google.common.collect.ImmutableList.toImmutableList<String?>())
                } else {
                    // This isn't allowMultiple, so the converter is a straightforward reversal.
                    return optionDef.getConverter().reverseForStarlark(value)
                }
            }

            /**
             * Validates that function outputs exactly the set of outputs it declares, as they were declared
             * (i.e. not canonicalized or in another form of the same label). More thorough checking (like
             * type checking of output values) is done elsewhere because it requires loading. see [ ][com.google.devtools.build.lib.analysis.starlark.StarlarkTransition.validate]
             * 
             * @param returnedKeySet actual key set of dict returned by starlark transition.
             * @param declaredReturnSettings list of build settings to return as declared by the 'outputs'
             * parameter (in their given form) to the transition definition.
             */
            @Throws(com.google.devtools.build.lib.analysis.config.StarlarkDefinedConfigTransition.ValidationException::class)
            private fun validateFunctionOutputsMatchesDeclaredOutputs(
                returnedKeySet: MutableSet<String?>, declaredReturnSettings: MutableCollection<String?>
            ) {
                if (returnedKeySet.containsAll(declaredReturnSettings)
                    && returnedKeySet.size() == declaredReturnSettings.size()
                ) {
                    return
                }

                val remainingOutputs: LinkedHashSet<String?> = LinkedHashSet<String?>(declaredReturnSettings)
                for (outputKey in returnedKeySet) {
                    if (!remainingOutputs.remove(outputKey)) {
                        throw com.google.devtools.build.lib.analysis.config.StarlarkDefinedConfigTransition.ValidationException(
                            java.lang.String.format("transition function returned undeclared output '%s'", outputKey)
                        )
                    }
                }

                if (!remainingOutputs.isEmpty()) {
                    throw com.google.devtools.build.lib.analysis.config.StarlarkDefinedConfigTransition.ValidationException(
                        java.lang.String.format(
                            "transition outputs [%s] were not defined by transition function",
                            java.lang.String.join(",", remainingOutputs)
                        )
                    )
                }
            }

            /**
             * Given a map of build settings to their values, return a map with the same build settings but
             * in their canonicalized string form to their values.
             * 
             * 
             * TODO(blaze-configurability): It would be nice if this method also returned a map of the
             * canonicalized settings to given settings so that when we throw the "unrecognized returned
             * option" warning we can show the setting as the user gave it as well as in its canonicalized
             * form.
             */
            @Throws(
                net.starlark.java.eval.EvalException::class,
                com.google.devtools.build.lib.analysis.config.StarlarkDefinedConfigTransition.ValidationException::class
            )
            private fun canonicalizeTransitionOutputDict(
                dict: MutableMap<String?, Any?>,
                repoMapping: com.google.devtools.build.lib.cmdline.RepositoryMapping?,
                parentLabel: com.google.devtools.build.lib.cmdline.Label,
                outputs: MutableCollection<String?>
            ): com.google.common.collect.ImmutableMap<com.google.devtools.build.lib.cmdline.Label?, Any?> {
                validateFunctionOutputsMatchesDeclaredOutputs(dict.keySet(), outputs)

                val canonicalizedToGiven: MutableMap<com.google.devtools.build.lib.cmdline.Label?, String?> =
                    HashMap<com.google.devtools.build.lib.cmdline.Label?, String?>()
                val canonicalizedDict: com.google.common.collect.ImmutableSortedMap.Builder<com.google.devtools.build.lib.cmdline.Label?, Any?> =
                    com.google.common.collect.ImmutableSortedMap.Builder<com.google.devtools.build.lib.cmdline.Label?, Any?>(
                        com.google.common.collect.Ordering.natural<com.google.devtools.build.lib.cmdline.Label?>()
                    )
                for (entry in dict.entrySet()) {
                    val returnedSetting: String = entry.getKey()
                    val label: com.google.devtools.build.lib.cmdline.Label?
                    try {
                        label = canonicalizeSetting(returnedSetting, repoMapping, parentLabel)
                    } catch (unused: LabelSyntaxException) {
                        throw net.starlark.java.eval.Starlark.errorf(
                            "Malformed label in transition return dictionary: '%s'", returnedSetting
                        )
                    }
                    val previousGiven = canonicalizedToGiven.put(label, returnedSetting)
                    if (previousGiven != null) {
                        throw net.starlark.java.eval.Starlark.errorf(
                            "Transition implementation function returns the same option '%s' in two different"
                                    + " keys: '%s' and '%s'",
                            label, returnedSetting, previousGiven
                        )
                    }
                    canonicalizedDict.put(label, entry.getValue())
                }
                return canonicalizedDict.buildOrThrow()
            }
        }
    }

    /** A transition implementation used only for Starlark-defined exec transitions.  */
    private class ExecTransition(
        impl: net.starlark.java.eval.StarlarkCallable,
        inputs: MutableList<String>,
        outputs: MutableList<String>,
        semantics: net.starlark.java.eval.StarlarkSemantics?,
        parentLabel: com.google.devtools.build.lib.cmdline.Label,
        location: net.starlark.java.syntax.Location?,
        repoMapping: com.google.devtools.build.lib.cmdline.RepositoryMapping?,
        disallowedOptions: MutableList<String?>
    ) : RegularTransition(
        impl, inputs, outputs, semantics, parentLabel, location, repoMapping, disallowedOptions
    ) {
        override fun isExecTransition(): Boolean {
            return true
        }
    }

    /** An exception for validating that a transition is properly constructed  */
    class ValidationException(message: String?) : java.lang.Exception(message) {
        companion object {
            @kotlin.jvm.JvmStatic
            @com.google.errorprone.annotations.FormatMethod
            fun format(format: String, vararg args: Any?): ValidationException {
                return com.google.devtools.build.lib.analysis.config.StarlarkDefinedConfigTransition.ValidationException(
                    java.lang.String.format(format, *args)
                )
            }
        }
    }

    companion object {
        /**
         * Returns a build settings in canonicalized form taking into account repository remappings.
         * Native options only have one form so they are always returned unchanged (i.e.
         * //command_line_option:<option-name>).
        </option-name> */
        @Throws(LabelSyntaxException::class)
        private fun canonicalizeSetting(
            setting: String,
            repoMapping: com.google.devtools.build.lib.cmdline.RepositoryMapping?,
            parentLabel: com.google.devtools.build.lib.cmdline.Label
        ): com.google.devtools.build.lib.cmdline.Label {
            // native options
            if (setting.startsWith(LabelConstants.COMMAND_LINE_OPTION_PREFIX)) {
                return com.google.devtools.build.lib.cmdline.Label.parseCanonical(setting)
            }
            return com.google.devtools.build.lib.cmdline.Label.parseWithPackageContext(
                setting, PackageContext.of(parentLabel.getPackageIdentifier(), repoMapping)
            )
        }

        /**
         * Canonicalize the given list of settings. Return a map of their canonicalized version to the
         * form they were given in. Along the way make sure that this list of settings doesn't contain two
         * label strings that look different but canonicalize to the same target.
         * 
         * @return a map of the canonicalized version of the build settings to the form the user gave
         * them. In the case of native options, the key and value of the entry are the same -
         * "//command_line_option:<option-name>"
        </option-name> */
        @Throws(net.starlark.java.eval.EvalException::class)
        private fun getCanonicalizedSettings(
            repoMapping: com.google.devtools.build.lib.cmdline.RepositoryMapping?,
            parentLabel: com.google.devtools.build.lib.cmdline.Label,
            settings: MutableList<String>,
            disallowedOptions: MutableList<String?>,
            inputsOrOutputs: Settings?
        ): com.google.common.collect.ImmutableMap<com.google.devtools.build.lib.cmdline.Label?, String?> {
            val canonicalizedToGiven: MutableMap<com.google.devtools.build.lib.cmdline.Label?, String?> =
                HashMap<com.google.devtools.build.lib.cmdline.Label?, String?>()
            for (setting in settings) {
                val canonicalizedSetting: com.google.devtools.build.lib.cmdline.Label
                try {
                    canonicalizedSetting = canonicalizeSetting(setting, repoMapping, parentLabel)
                } catch (unused: LabelSyntaxException) {
                    throw net.starlark.java.eval.Starlark.errorf(
                        "Malformed label in transition %s parameter: '%s'", inputsOrOutputs, setting
                    )
                }
                if (canonicalizedSetting
                        .getPackageIdentifier()
                    == LabelConstants.COMMAND_LINE_OPTION_PACKAGE_IDENTIFIER
                ) {
                    val optionName: String? = canonicalizedSetting.getName()
                    if (disallowedOptions.contains(optionName)) {
                        throw net.starlark.java.eval.Starlark.errorf(
                            "Option '%s' is not allowed in transitions %s.", optionName, inputsOrOutputs
                        )
                    }
                }
                val previousSetting = canonicalizedToGiven.put(canonicalizedSetting, setting)
                if (previousSetting != null) {
                    throw net.starlark.java.eval.Starlark.errorf(
                        "Transition declares duplicate build setting '%s' in %s (specified as '%s' and '%s')",
                        canonicalizedSetting, inputsOrOutputs, setting, previousSetting
                    )
                }
            }
            return com.google.common.collect.ImmutableSortedMap.copyOf<com.google.devtools.build.lib.cmdline.Label?, String?>(
                canonicalizedToGiven
            )
        }

        @Throws(net.starlark.java.eval.EvalException::class)
        fun newRegularTransition(
            impl: net.starlark.java.eval.StarlarkCallable,
            inputs: MutableList<String>,
            outputs: MutableList<String>,
            semantics: net.starlark.java.eval.StarlarkSemantics?,
            parentLabel: com.google.devtools.build.lib.cmdline.Label,
            location: net.starlark.java.syntax.Location?,
            repoMapping: com.google.devtools.build.lib.cmdline.RepositoryMapping?,
            disallowedOptions: MutableList<String?>
        ): StarlarkDefinedConfigTransition {
            return RegularTransition(
                impl, inputs, outputs, semantics, parentLabel, location, repoMapping, disallowedOptions
            )
        }

        @Throws(net.starlark.java.eval.EvalException::class)
        fun newExecTransition(
            impl: net.starlark.java.eval.StarlarkCallable,
            inputs: MutableList<String>,
            outputs: MutableList<String>,
            semantics: net.starlark.java.eval.StarlarkSemantics?,
            parentLabel: com.google.devtools.build.lib.cmdline.Label,
            location: net.starlark.java.syntax.Location?,
            repoMapping: com.google.devtools.build.lib.cmdline.RepositoryMapping?,
            disallowedOptions: MutableList<String?>
        ): StarlarkDefinedConfigTransition {
            return ExecTransition(
                impl, inputs, outputs, semantics, parentLabel, location, repoMapping, disallowedOptions
            )
        }

        @Throws(net.starlark.java.eval.EvalException::class)
        fun newAnalysisTestTransition(
            changedSettings: MutableMap<String?, Any?>,
            repoMapping: com.google.devtools.build.lib.cmdline.RepositoryMapping?,
            parentLabel: com.google.devtools.build.lib.cmdline.Label,
            location: net.starlark.java.syntax.Location?,
            disallowedOptions: MutableList<String?>
        ): StarlarkDefinedConfigTransition {
            return AnalysisTestTransition(
                changedSettings, repoMapping, parentLabel, location, disallowedOptions
            )
        }
    }
}
