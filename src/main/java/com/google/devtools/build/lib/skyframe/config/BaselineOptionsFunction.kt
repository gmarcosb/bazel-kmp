// Copyright 2023 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.skyframe.config

import com.google.devtools.build.lib.analysis.PlatformOptions

/** A builder for [BaselineOptionsValue] instances.  */
class BaselineOptionsFunction(minimalVersionToInject: com.google.devtools.build.skyframe.Version?) : SkyFunction {
    private val minimalVersionToInject: com.google.devtools.build.skyframe.Version

    init {
        this.minimalVersionToInject =
            com.google.common.base.Preconditions.checkNotNull<com.google.devtools.build.skyframe.Version>(
                minimalVersionToInject
            )
    }

    @Throws(java.lang.InterruptedException::class, BaselineOptionsFunctionException::class)
    override fun compute(skyKey: SkyKey, env: SkyFunction.Environment): SkyValue? {
        env.injectVersionForNonHermeticFunction(minimalVersionToInject)

        val key: BaselineOptionsValue.Key = skyKey.argument() as BaselineOptionsValue.Key

        var rawBaselineOptions: BuildOptions?
        if (key.afterExecTransition) {
            // Use the precomputed baseline exec
            rawBaselineOptions = BASELINE_EXEC_CONFIGURATION.get(env)
        } else {
            // Use the standard baseline
            rawBaselineOptions = BASELINE_CONFIGURATION.get(env)
        }

        // Some test infrastructure only creates mock or partial top-level BuildOptions such that
        // PlatformOptions or even CoreOptions might not be included.
        // In that case, is not worth doing any special processing of the baseline.
        if (rawBaselineOptions.hasNoConfig()) {
            return BaselineOptionsValue.create(rawBaselineOptions)
        }

        if (key.trimTestOptions) {
            rawBaselineOptions = TestTrimmingLogic.trim(rawBaselineOptions)
        }

        // First, make sure platform_mappings applied to the top-level baseline option.
        val mappedBaselineOptions: BuildOptions? = mapBuildOptions(env, rawBaselineOptions)
        if (mappedBaselineOptions == null) {
            return null
        }
        var adjustedBaselineOptions: BuildOptions? = mappedBaselineOptions

        if (key.newPlatform() != null) {
            // Clone for safety as-is the standard for all transitions.
            adjustedBaselineOptions = adjustedBaselineOptions.clone()
            adjustedBaselineOptions
                .get(PlatformOptions::class.java)
                .setPlatforms(com.google.common.collect.ImmutableList.of<E?>(key.newPlatform()))
        }

        // Re-apply platform_mappings if we updated the platform.
        // This initially seems somewhat redundant with the application above; however, this is meant to
        // better track how the top-level build options will initially have platform mappings applied
        // before some transition (e.g exec transition) changes the platform to cause another
        // application of platform mappings. Platforms in platform_mappings may change different sets of
        // options so applying both should lead to better baselines.
        // TODO(twigg,jcater): Evaluate and reconsider this 'scenario'.
        var remappedAdjustedBaselineOptions: BuildOptions? = adjustedBaselineOptions
        if (key.newPlatform() != null) {
            remappedAdjustedBaselineOptions = mapBuildOptions(env, remappedAdjustedBaselineOptions)
            if (remappedAdjustedBaselineOptions == null) {
                return null
            }
        }

        return BaselineOptionsValue.create(remappedAdjustedBaselineOptions)
    }

    private class BaselineOptionsFunctionException(e: java.lang.Exception?) :
        SkyFunctionException(e, Transience.PERSISTENT)

    companion object {
        // Don't use these directly. Instead, use the BuildOptions obtained from this function, which
        // applies the appropriate trimming and transition logic to reduce Skyframe invalidation.
        // Unsharable because of complications in deserializing BuildOptions on startup due to caching.
        @kotlin.jvm.JvmField
        val BASELINE_CONFIGURATION: Precomputed<BuildOptions?> =
            Precomputed.createUnshareable<BuildOptions?>("baseline_configuration")
        @kotlin.jvm.JvmField
        val BASELINE_EXEC_CONFIGURATION: Precomputed<BuildOptions?> =
            Precomputed.createUnshareable<BuildOptions?>("baseline_exec_configuration")

        @Throws(java.lang.InterruptedException::class, BaselineOptionsFunctionException::class)
        private fun mapBuildOptions(env: SkyFunction.Environment, rawBaselineOptions: BuildOptions): BuildOptions? {
            // Baseline options have no need to contain scope info. Set all scopes to the default type so
            // that BuildConfigurationKeyFunction doesn't attempt to apply scopes, which would lead to a
            // cycle as this requires knowing the baseline options.
            val builder: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                rawBaselineOptions.toBuilder()
            rawBaselineOptions
                .getStarlarkOptions()
                .forEach(
                    { key, value ->
                        builder.addScopeType(key, ScopeType(Scope.ScopeType.DEFAULT))
                        builder.removeOnLeaveScopeValue(key)
                    })
            val bckvk: com.google.devtools.build.lib.skyframe.config.BuildConfigurationKeyValue.Key? =
                com.google.devtools.build.lib.skyframe.config.BuildConfigurationKeyValue.Key.Companion.create(builder.build())
            try {
                val buildConfigurationKeyValue: BuildConfigurationKeyValue? =
                    env.getValueOrThrow<com.google.devtools.common.options.OptionsParsingException?, PlatformMappingException?, InvalidPlatformException?>(
                        bckvk,
                        com.google.devtools.common.options.OptionsParsingException::class.java,
                        PlatformMappingException::class.java,
                        InvalidPlatformException::class.java
                    ) as BuildConfigurationKeyValue?
                if (buildConfigurationKeyValue == null) {
                    return null
                }
                return buildConfigurationKeyValue.buildConfigurationKey().getOptions()
            } catch (e: PlatformMappingException) {
                throw BaselineOptionsFunctionException(e)
            } catch (e: com.google.devtools.common.options.OptionsParsingException) {
                throw BaselineOptionsFunctionException(e)
            } catch (e: InvalidPlatformException) {
                throw BaselineOptionsFunctionException(e)
            }
        }
    }
}
