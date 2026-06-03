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
package com.google.devtools.build.lib.analysis.configuredtargets

import com.google.devtools.build.lib.actions.ActionLookupKey

/**
 * An abstract implementation of ConfiguredTarget in which all properties are assigned trivial
 * default values.
 */
abstract class AbstractConfiguredTarget protected constructor(
    actionLookupKey: ActionLookupKey?,
    visibility: NestedSet<PackageGroupContents?>?
) : ConfiguredTarget, VisibilityProvider {
    // This should really never be null, but is null in two cases.
    // 1. MergedConfiguredTarget: these are ephemeral and never added to the Skyframe graph.
    // 2. PackageSpecificationProvider.EMPTY: it is used here only to inject an empty
    //    PackageSpecificationProvider.
    // TODO(b/281522692): The existence of these cases suggest that there should be some additional
    // abstraction that does not have a key.
    private val actionLookupKey: ActionLookupKey?

    private val visibility: NestedSet<PackageGroupContents?>?

    init {
        this.actionLookupKey = actionLookupKey
        this.visibility = visibility
    }

    public override fun getLookupKey(): ActionLookupKey? {
        return actionLookupKey
    }

    public override fun isImmutable(): Boolean {
        return true // all Targets are immutable and Starlark-hashable
    }

    public override fun getVisibility(): NestedSet<PackageGroupContents?>? {
        return visibility
    }

    override fun toString(): String {
        return "ConfiguredTarget(" + getLabel() + ", " + getConfigurationChecksum() + ")"
    }

    public override fun <P : TransitiveInfoProvider?> getProvider(provider: java.lang.Class<P?>): P? {
        AnalysisUtils.Companion.checkProvider<P?>(provider)
        if (provider.isAssignableFrom(getClass())) {
            return provider.cast(this)
        } else {
            return null
        }
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    public override fun getValue(semantics: StarlarkSemantics, name: String): Any? {
        if (!SPECIAL_FIELD_NAMES.contains(name)) {
            throw Starlark.errorf(
                "Accessing providers via the field syntax on structs is deprecated and removed."
            )
        } else if (semantics.getBool(
                BuildLanguageOptions.INCOMPATIBLE_DISABLE_TARGET_DEFAULT_PROVIDER_FIELDS
            )
            && DEFAULT_PROVIDER_FIELDS.contains(name)
        ) {
            throw Starlark.errorf(
                ("Accessing the default provider in this manner is deprecated and will be removed soon. "
                        + "It may be temporarily re-enabled by setting "
                        + "--incompatible_disable_target_default_provider_fields=false. See "
                        + "https://github.com/bazelbuild/bazel/issues/20183 for details.")
            )
        }
        return getValue(name)
    }

    public override fun getValue(name: String): Any? {
        return when (name) {
            LABEL_FIELD -> getLabel()
            ACTIONS_FIELD_NAME -> {
                // Depending on subclass, the 'actions' field will either be unsupported or of type
                // java.util.List, which needs to be converted to Sequence before being returned.
                val result: Any? = get(name)
                if (result != null) Starlark.fromJava(result, null) else null
            }

            else -> get(name)
        }
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    public override fun getIndex(semantics: StarlarkSemantics?, key: Any?): Any {
        // Only call `getKey()` on unexported Providers to avoid crashing. Users can write:
        // rule(implementation = lambda ctx: ctx.attr.input[provider()], attr = {"input": ...})
        val constructor: Provider = selectExportedProvider(key, semantics, "index")
        val declaredProvider: Any? = get(constructor.getKey())
        if (declaredProvider != null) {
            return declaredProvider
        }
        throw Starlark.errorf(
            "%s%s doesn't contain declared provider '%s'",
            Starlark.repr(this, semantics),
            getRuleClassStringForError(),
            constructor.getPrintableName()
        )
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    public override fun containsKey(semantics: StarlarkSemantics?, key: Any?): Boolean {
        return get(selectExportedProvider(key, semantics, "query").getKey()) != null
    }

    public override fun getErrorMessageForUnknownField(name: String?): String? {
        return null
    }

    public override fun getFieldNames(): com.google.common.collect.ImmutableList<String?> {
        val result: com.google.common.collect.ImmutableList.Builder<String?> =
            com.google.common.collect.ImmutableList.builder<String?>()
        result.addAll(
            com.google.common.collect.ImmutableList.of<String?>(
                DATA_RUNFILES_FIELD,
                DEFAULT_RUNFILES_FIELD,
                LABEL_FIELD,
                FILES_FIELD,
                FilesToRunProvider.Companion.STARLARK_NAME
            )
        )
        if (get(OutputGroupInfo.Companion.STARLARK_CONSTRUCTOR) != null) {
            result.add(OutputGroupInfo.Companion.STARLARK_NAME)
        }
        addExtraStarlarkKeys(java.util.function.Consumer { element: String? -> result.add(element) })
        return result.build()
    }

    open fun addExtraStarlarkKeys(result: java.util.function.Consumer<String?>?) {}

    private fun getDefaultProvider(): DefaultInfo {
        return DefaultInfo.Companion.build(this)
    }

    /** Returns a declared provider provided by this target. Only meant to use from Starlark.  */
    public override fun get(providerKey: Provider.Key): Info? {
        if (providerKey.equals(DefaultInfo.Companion.PROVIDER.getKey())) {
            return getDefaultProvider()
        }
        return rawGetStarlarkProvider(providerKey)
    }

    /** Returns a value provided by this target. Only meant to use from Starlark.  */
    public override fun get(providerKey: String): Any? {
        return when (providerKey) {
            FILES_FIELD -> getDefaultProvider().getFiles()
            DEFAULT_RUNFILES_FIELD -> getDefaultProvider().getDefaultRunfiles()
            DATA_RUNFILES_FIELD -> getDefaultProvider().getDataRunfiles()
            FilesToRunProvider.Companion.STARLARK_NAME -> getDefaultProvider().getFilesToRun()
            OutputGroupInfo.Companion.STARLARK_NAME -> get(OutputGroupInfo.Companion.STARLARK_CONSTRUCTOR)
            else -> rawGetStarlarkProvider(providerKey)
        }
    }

    /** Implement in subclasses to get a Starlark provider for a given `providerKey`.  */
    protected abstract fun rawGetStarlarkProvider(providerKey: Provider.Key?): Info?

    /** Implement in subclasses to get a Starlark provider for a given `providerKey`.  */
    protected abstract fun rawGetStarlarkProvider(providerKey: String?): Any?

    open fun getRuleClassString(): String? {
        return ""
    }

    // All main target classes must override this method to provide more descriptive strings.
    // Exceptions are currently EnvironmentGroupConfiguredTarget and PackageGroupConfiguredTarget.
    public override fun repr(printer: net.starlark.java.eval.Printer, semantics: StarlarkSemantics?) {
        printer.append("<unknown target " + getLabel() + ">")
    }

    private fun getRuleClassStringForError(): String {
        return if (getRuleClassString().isEmpty()) "" else " (rule '" + getRuleClassString() + "')"
    }

    /**
     * Selects the provider identified by `key`, throwing a Starlark error if the key is not a
     * provider or not exported.
     */
    @Throws(net.starlark.java.eval.EvalException::class)
    private fun selectExportedProvider(key: Any?, semantics: StarlarkSemantics?, operation: String?): Provider {
        if (key !is Provider) {
            throw Starlark.errorf(
                "Type Target only supports %sing by object constructors, got %s instead",
                operation, Starlark.type(key)
            )
        }
        if (!key.isExported()) {
            throw Starlark.errorf(
                "%s%s only supports %sing by exported providers. Assign the provider a name "
                        + "in a top-level assignment statement.",
                Starlark.repr(this, semantics), getRuleClassStringForError(), operation
            )
        }
        return key
    }

    /**
     * Returns a [Dict] of provider names to their values for a configured target, intended to
     * be called from [.getProvidersDictForQuery].
     * 
     * 
     * [.getProvidersDictForQuery] is intended to be used from Starlark query output methods,
     * so all values must be accessible in Starlark. If the value of a provider is not convertible to
     * a Starlark value, that name/value pair is left out of the [Dict].
     */
    fun toProvidersDictForQuery(providers: TransitiveInfoProviderMap): Dict<String?, Any?>? {
        val dict: net.starlark.java.eval.Dict.Builder<String?, Any?> = Dict.builder<String?, Any?>()
        for (i in 0..<providers.getProviderCount()) {
            tryAddProviderForQuery(
                dict, providers.getProviderKeyAt(i), providers.getProviderInstanceAt(i)
            )
        }
        // DefaultInfo is not stored as a provider, but Starlark targets still observe it on
        // dependencies.
        tryAddProviderForQuery(dict, DefaultInfo.Companion.PROVIDER.getKey(), DefaultInfo.Companion.build(this))
        return dict.buildImmutable()
    }

    companion object {
        // Accessors for Starlark
        private const val DATA_RUNFILES_FIELD = "data_runfiles"
        private const val DEFAULT_RUNFILES_FIELD = "default_runfiles"

        /**
         * The name of the key for the 'actions' synthesized provider.
         * 
         * 
         * If you respond to this key you are expected to return a list of actions belonging to this
         * configured target.
         */
        const val ACTIONS_FIELD_NAME: String = "actions"

        // A set containing all field names which may be specially handled (and thus may not be
        // attributed to normal user-specified providers).
        private val SPECIAL_FIELD_NAMES: com.google.common.collect.ImmutableSet<String?> =
            com.google.common.collect.ImmutableSet.< String > of < kotlin . String ? > (
                    LABEL_FIELD,
        FILES_FIELD,
        AbstractConfiguredTarget.Companion.DEFAULT_RUNFILES_FIELD,
        AbstractConfiguredTarget.Companion.DATA_RUNFILES_FIELD,
        FilesToRunProvider.Companion.STARLARK_NAME,
        OutputGroupInfo.Companion.STARLARK_NAME,
        AbstractConfiguredTarget.Companion.ACTIONS_FIELD_NAME)

        private val DEFAULT_PROVIDER_FIELDS: com.google.common.collect.ImmutableSet<String?> =
            com.google.common.collect.ImmutableSet.of<String?>(
                DEFAULT_RUNFILES_FIELD,
                DATA_RUNFILES_FIELD,
                FILES_FIELD,
                FilesToRunProvider.Companion.STARLARK_NAME,
                OutputGroupInfo.Companion.STARLARK_NAME
            )

        /**
         * Attempts to add a provider instance to `dict` under an unspecified stringification of the
         * given key. Takes no action if the provider instance is not a valid Starlark value or if the key
         * is of an unknown type.
         * 
         * 
         * Intended to be called from [.getProvidersDictForQuery].
         */
        fun tryAddProviderForQuery(
            dict: net.starlark.java.eval.Dict.Builder<String?, Any?>, key: Any, providerInstance: Any?
        ) {
            // The key may be of many types, but we need a string for the intended use.
            val keyAsString: String?
            if (key is String) {
                keyAsString = key
            } else if (key is Provider.Key) {
                if (key is StarlarkProvider.Key) {
                    keyAsString = key.getExtensionLabel() + "%" + key.getExportedName()
                } else {
                    keyAsString = key.toString()
                }
            } else if (key is java.lang.Class<*>) {
                keyAsString = key.getSimpleName()
            } else {
                // ???
                return
            }
            try {
                dict.put(keyAsString, Starlark.fromJava(providerInstance,  /* mutability= */null))
            } catch (e: InvalidStarlarkValueException) {
                // This is OK. If this is not a valid StarlarkValue, we just leave it out of the map.
            }
        }
    }
}
