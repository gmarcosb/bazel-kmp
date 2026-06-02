// Copyright 2017 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.rules.config

import com.google.devtools.build.lib.analysis.TransitiveInfoCollection

/** Provider for exporting value and valid value predicate of feature flags to consuming targets.  */ // TODO(adonovan): rename this to *Info and its constructor to *Provider.
@com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable
class ConfigFeatureFlagProvider private constructor(
    private val value: String?,
    /** Gets the current value of the flag in the flag's current configuration.  */
    val error: String?, validityPredicate: com.google.common.base.Predicate<String?>
) : NativeInfo(), ConfigFeatureFlagProviderApi {
    private val validityPredicate: com.google.common.base.Predicate<String?>

    init {
        this.validityPredicate = validityPredicate
    }

    val provider: BuiltinProvider<ConfigFeatureFlagProvider?>
        get() = STARLARK_CONSTRUCTOR

    /**
     * A constructor callable from Starlark for OutputGroupInfo: `config_common.FeatureFlagInfo(value="...")`
     */
    @net.starlark.java.annot.StarlarkBuiltin(name = "FeatureFlagInfo", documented = false)
    @com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable
    private class Constructor :
        BuiltinProvider<ConfigFeatureFlagProvider?>(STARLARK_NAME, ConfigFeatureFlagProvider::class.java),
        net.starlark.java.eval.StarlarkValue {
        @net.starlark.java.annot.StarlarkMethod(
            name = "FeatureFlagInfo",
            documented = false,
            parameters = [net.starlark.java.annot.Param(name = "value", named = true)],
            selfCall = true
        )
        fun selfcall(value: String?): ConfigFeatureFlagProvider {
            return create(value, null, com.google.common.base.Predicates.alwaysTrue<String?>())
        }

        override fun repr(
            printer: net.starlark.java.eval.Printer,
            semantics: net.starlark.java.eval.StarlarkSemantics?
        ) {
            printer.append("<function FeatureFlagInfo>")
        }
    }

    val flagValue: String?
        /**
         * Gets the current value of the flag in the flag's current configuration.
         * 
         * 
         * Throws EvalException when getError() is non-empty.
         */
        get() {
            if (!com.google.common.base.Strings.isNullOrEmpty(this.error)) {
                return null
            }
            return value
        }

    /** Returns whether this value is valid for this flag.  */
    override fun isValidValue(value: String?): Boolean {
        return validityPredicate.apply(value)
    }

    // ConfigFeatureFlagProvider instances should all be unique, so we override the default
    // equals and hashCode from Info to ensure that. SCO's toString is fine, however.
    override fun equals(other: Any?): Boolean {
        return other === this
    }

    override fun hashCode(): Int {
        return java.lang.System.identityHashCode(this)
    }

    companion object {
        /** Name used in Starlark for accessing ConfigFeatureFlagProvider.  */
        const val STARLARK_NAME: String = "FeatureFlagInfo"

        /**
         * Constructor and identifier for ConfigFeatureFlagProvider. This is the value of `config_common.FeatureFlagInfo`.
         */
        val STARLARK_CONSTRUCTOR: BuiltinProvider<ConfigFeatureFlagProvider?> =
            com.google.devtools.build.lib.rules.config.ConfigFeatureFlagProvider.Constructor()

        val REQUIRE_CONFIG_FEATURE_FLAG_PROVIDER: RequiredProviders? =
            RequiredProviders.acceptAnyBuilder().addStarlarkSet(
                com.google.common.collect.ImmutableSet.of<E?>(
                    id()
                )
            ).build()

        /** Creates a new ConfigFeatureFlagProvider with the given value and valid value predicate.  */
        fun create(
            value: String?, potentialError: String?, isValidValue: com.google.common.base.Predicate<String?>
        ): ConfigFeatureFlagProvider {
            return ConfigFeatureFlagProvider(value, potentialError, isValidValue)
        }

        fun id(): StarlarkProviderIdentifier {
            return STARLARK_CONSTRUCTOR.id()
        }

        /** Retrieves and casts the provider from the given target.  */
        fun fromTarget(target: TransitiveInfoCollection): ConfigFeatureFlagProvider {
            return target.get(STARLARK_CONSTRUCTOR)
        }
    }
}
