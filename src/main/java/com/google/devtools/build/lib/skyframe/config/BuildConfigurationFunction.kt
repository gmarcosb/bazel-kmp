// Copyright 2015 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.analysis.constraints.ConstraintConstants.CPU_CONSTRAINT_SETTING

/** A builder for [BuildConfigurationValue] instances.  */
class BuildConfigurationFunction(directories: BlazeDirectories?, ruleClassProvider: RuleClassProvider?) : SkyFunction {
    private val directories: BlazeDirectories?
    private val ruleClassProvider: ConfiguredRuleClassProvider?
    private val fragmentFactory: FragmentFactory = FragmentFactory()

    init {
        this.directories = directories
        this.ruleClassProvider = ruleClassProvider as ConfiguredRuleClassProvider?
    }

    @Throws(java.lang.InterruptedException::class, BuildConfigurationFunctionException::class)
    override fun compute(skyKey: SkyKey, env: SkyFunction.Environment): SkyValue? {
        val starlarkSemantics: net.starlark.java.eval.StarlarkSemantics? = PrecomputedValue.STARLARK_SEMANTICS.get(env)
        if (starlarkSemantics == null) {
            return null
        }
        val key: BuildConfigurationKey = skyKey.argument() as BuildConfigurationKey

        val targetOptions: BuildOptions = key.getOptions()
        val baselineOptions: java.util.Optional<BuildOptions?>? = getBaselineOptions(env, targetOptions)
        if (baselineOptions == null) {
            return null
        }

        val platformCpu = getPlatformCpu(env, targetOptions)
        if (platformCpu == null) {
            return null
        }

        try {
            val configurationValue: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                BuildConfigurationValue.create(
                    targetOptions,
                    baselineOptions.orElse(null),
                    starlarkSemantics.getBool(
                        BuildLanguageOptions.EXPERIMENTAL_SIBLING_REPOSITORY_LAYOUT
                    ),
                    platformCpu,  // Arguments below this are server-global.
                    directories,
                    ruleClassProvider,
                    fragmentFactory
                )
            env.getListener().post(ConfigurationValueEvent.create(configurationValue))
            return configurationValue
        } catch (e: InvalidConfigurationException) {
            throw BuildConfigurationFunctionException(e)
        }
    }

    private class BuildConfigurationFunctionException(e: java.lang.Exception?) :
        SkyFunctionException(e, Transience.PERSISTENT)

    companion object {
        @Throws(java.lang.InterruptedException::class)
        private fun getPlatformCpu(env: SkyFunction.Environment, targetOptions: BuildOptions): String? {
            if (targetOptions.get(PlatformOptions::class.java) == null) {
                return ""
            }

            val platformLabel: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                targetOptions.get(PlatformOptions::class.java).computeTargetPlatform()

            val coreOptions: CoreOptions = targetOptions.get(CoreOptions::class.java)
            val overridePlatformCpuName: java.util.Optional<String?> =
                coreOptions.getPlatformCpuNameOverride(platformLabel)
            if (overridePlatformCpuName.isPresent()) {
                return overridePlatformCpuName.get()
            }

            val platformValue: PlatformValue? =
                env.getValue(
                    PlatformValue.key(platformLabel, coreOptions.getCommandLineFlagAliasesMap())
                ) as PlatformValue?
            if (platformValue == null) {
                return null
            }

            val cpuConstraint: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                platformValue.platformInfo().constraints().get(CPU_CONSTRAINT_SETTING)
            if (cpuConstraint == null) {
                return ""
            }

            return cpuConstraint.label().getName()
        }

        /**
         * Determine the baseline options to use for tracking changes.
         * 
         * 
         * Returns `null` if a Skyframe restart is needed, or an [Optional] with either the
         * baseline options to use, or none if there is no valid baseline.
         */
        @Throws(java.lang.InterruptedException::class, BuildConfigurationFunctionException::class)
        private fun getBaselineOptions(
            env: SkyFunction.Environment, targetOptions: BuildOptions
        ): java.util.Optional<BuildOptions?>? {
            if (targetOptions.hasNoConfig()) {
                return java.util.Optional.empty<BuildOptions?>()
            }

            val coreOptions: CoreOptions = targetOptions.get(CoreOptions::class.java)
            val platformOptions: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                targetOptions.get(PlatformOptions::class.java)
            // In practice, platforms should always be 'well-formed' and contain at most one Label.
            var newPlatform: Label? = null
            if (platformOptions != null
                && coreOptions.usePlatformInOutputDir(platformOptions.computeTargetPlatform())
            ) {
                newPlatform = platformOptions.computeTargetPlatform()
            }

            try {
                val baselineOptionsValue: BaselineOptionsValue? =
                    env.getValueOrThrow<E?>(
                        BaselineOptionsValue.key(
                            coreOptions.getIsExec(),
                            !targetOptions.contains(TestConfiguration.TestOptions::class.java),
                            newPlatform
                        ),
                        StarlarkExecTransitionLoadingException::class.java
                    ) as BaselineOptionsValue?
                if (baselineOptionsValue == null) {
                    return null
                }
                return java.util.Optional.of<T?>(baselineOptionsValue.toOptions())
            } catch (e: StarlarkExecTransitionLoadingException) {
                throw BuildConfigurationFunctionException(InvalidConfigurationException(e))
            }
        }
    }
}
