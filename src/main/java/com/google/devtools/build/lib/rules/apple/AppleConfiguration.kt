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
package com.google.devtools.build.lib.rules.apple

import com.google.common.annotations.VisibleForTesting
import com.google.common.base.Joiner
import com.google.common.base.Preconditions
import com.google.common.base.Strings
import com.google.common.collect.ImmutableList
import com.google.devtools.build.lib.analysis.config.BuildOptions
import com.google.devtools.build.lib.concurrent.ThreadSafety
import com.google.devtools.build.lib.util.CPU
import net.starlark.java.eval.EvalException
import net.starlark.java.eval.Tuple
import kotlin.collections.ArrayList
import kotlin.collections.MutableList
import kotlin.collections.MutableMap

/** A configuration containing flags required for Apple platforms and tools.  */
@ThreadSafety.Immutable
@RequiresOptions(options = [AppleCommandLineOptions::class])
class AppleConfiguration(buildOptions: BuildOptions) : Fragment(), AppleConfigurationApi {
    val applePlatformType: String
    private val xcodeConfigLabel: Label
    val options: AppleCommandLineOptions
    private val appleCpus: AppleCpus

    @get:Throws(EvalException::class)
    val xcodeVersionFlag: String?
    private val iosSdkVersionFlag: DottedVersion?
    private val macOsSdkVersionFlag: DottedVersion?
    private val tvOsSdkVersionFlag: DottedVersion?
    private val watchOsSdkVersionFlag: DottedVersion?
    private val iosMinimumOsFlag: DottedVersion?
    private val macosMinimumOsFlag: DottedVersion?
    private val tvosMinimumOsFlag: DottedVersion?
    private val watchosMinimumOsFlag: DottedVersion?
    private val preferMutualXcode: Boolean
    private val includeXcodeExecRequirements: Boolean
    private val disableAppleFragment: Boolean

    init {
        val options: AppleCommandLineOptions = buildOptions.get(AppleCommandLineOptions::class.java)
        this.options = options
        this.appleCpus = AppleCpus.Companion.create(options)
        this.applePlatformType =
            Preconditions.checkNotNull<String>(options.getApplePlatformType(), "applePlatformType")
        this.xcodeConfigLabel =
            Preconditions.checkNotNull<Label>(options.getXcodeVersionConfig(), "xcodeConfigLabel")
        // AppleConfiguration should not have this knowledge. This is a temporary workaround
        // for Starlarkification, until apple rules are toolchainized.
        this.xcodeVersionFlag = options.getXcodeVersion()
        this.iosSdkVersionFlag = DottedVersion.Companion.maybeUnwrap(options.getIosSdkVersion())
        this.macOsSdkVersionFlag = DottedVersion.Companion.maybeUnwrap(options.getMacOsSdkVersion())
        this.tvOsSdkVersionFlag = DottedVersion.Companion.maybeUnwrap(options.getTvOsSdkVersion())
        this.watchOsSdkVersionFlag = DottedVersion.Companion.maybeUnwrap(options.getWatchOsSdkVersion())
        this.iosMinimumOsFlag = DottedVersion.Companion.maybeUnwrap(options.getIosMinimumOs())
        this.macosMinimumOsFlag = DottedVersion.Companion.maybeUnwrap(options.getMacosMinimumOs())
        this.tvosMinimumOsFlag = DottedVersion.Companion.maybeUnwrap(options.getTvosMinimumOs())
        this.watchosMinimumOsFlag = DottedVersion.Companion.maybeUnwrap(options.getWatchosMinimumOs())
        this.preferMutualXcode = options.getPreferMutualXcode()
        this.includeXcodeExecRequirements = options.getIncludeXcodeExecutionRequirements()
        this.disableAppleFragment = options.getDisableAppleFragment()
    }

    /** A class that contains information pertaining to Apple CPUs.  */
    @AutoValue
    abstract class AppleCpus {
        abstract fun appleSplitCpu(): String

        abstract fun iosMultiCpus(): ImmutableList<String?>?

        abstract fun visionosCpus(): ImmutableList<String?>?

        abstract fun watchosCpus(): ImmutableList<String?>?

        abstract fun tvosCpus(): ImmutableList<String?>?

        abstract fun macosCpus(): ImmutableList<String?>?

        companion object {
            fun create(options: AppleCommandLineOptions): AppleCpus {
                val appleSplitCpu =
                    Preconditions.checkNotNull<String>(options.getAppleSplitCpu(), "appleSplitCpu")
                val iosMultiCpus =
                    if (options.getIosMultiCpus() == null || options.getIosMultiCpus().isEmpty())
                        ImmutableList.of<String?>(DEFAULT_IOS_CPU)
                    else
                        ImmutableList.copyOf<String?>(options.getIosMultiCpus())
                val visionosCpus =
                    if (options.getVisionosCpus() == null || options.getVisionosCpus().isEmpty())
                        ImmutableList.of<String?>(AppleCommandLineOptions.Companion.DEFAULT_VISIONOS_CPU)
                    else
                        ImmutableList.copyOf<String?>(options.getVisionosCpus())
                val watchosCpus =
                    if (options.getWatchosCpus() == null || options.getWatchosCpus().isEmpty())
                        ImmutableList.of<String?>(AppleCommandLineOptions.Companion.DEFAULT_WATCHOS_CPU)
                    else
                        ImmutableList.copyOf<String?>(options.getWatchosCpus())
                val tvosCpus =
                    if (options.getTvosCpus() == null || options.getTvosCpus().isEmpty())
                        ImmutableList.of<String?>(AppleCommandLineOptions.Companion.DEFAULT_TVOS_CPU)
                    else
                        ImmutableList.copyOf<String?>(options.getTvosCpus())
                val macosCpus =
                    if (options.getMacosCpus() == null || options.getMacosCpus().isEmpty())
                        ImmutableList.of<String?>(AppleCommandLineOptions.Companion.DEFAULT_MACOS_CPU)
                    else
                        ImmutableList.copyOf<String?>(options.getMacosCpus())

                return AutoValue_AppleConfiguration_AppleCpus(
                    appleSplitCpu, iosMultiCpus, visionosCpus, watchosCpus, tvosCpus, macosCpus
                )
            }
        }
    }

    public override fun shouldInclude(): Boolean {
        return !disableAppleFragment
    }

    @get:Throws(EvalException::class)
    val appleCpusForStarlark: StructApi
        get() {
            val fields: MutableMap<String?, Any?> =
                HashMap<String?, Any?>()
            fields.put("apple_split_cpu", appleCpus.appleSplitCpu())
            fields.put("ios_multi_cpus", Tuple.copyOf(appleCpus.iosMultiCpus()))
            fields.put("visionos_cpus", Tuple.copyOf(appleCpus.visionosCpus()))
            fields.put("watchos_cpus", Tuple.copyOf(appleCpus.watchosCpus()))
            fields.put("tvos_cpus", Tuple.copyOf(appleCpus.tvosCpus()))
            fields.put("macos_cpus", Tuple.copyOf(appleCpus.macosCpus()))
            return StructProvider.STRUCT.create(fields, "")
        }

    val singleArchitecture: String
        /**
         * Gets the single "effective" architecture for this configuration's [PlatformType] (for
         * example, "i386" or "arm64").
         * 
         * 
         * Single effective architecture is determined using the following rules:
         * 
         * 
         *  1. If `--apple_split_cpu` is set (done via prior configuration transition), then that
         * is the effective architecture.
         *  1. If the multi cpus flag (e.g. `--ios_multi_cpus`) is set and non-empty, then the
         * first such architecture is returned.
         *  1. In the case of iOS, use `--cpu` if it leads with "ios_" for backwards
         * compatibility.
         *  1. In the case of macOS, use `--cpu` if it leads with "darwin_" for backwards
         * compatibility.
         *  1. Use the default.
         * 
         */
        get() = getUnprefixedAppleCpu(applePlatformType, appleCpus)

    val singleArchPlatform: ApplePlatform
        /**
         * Gets the single "effective" platform for this configuration's [PlatformType] and
         * architecture.
         */
        get() = ApplePlatform.Companion.forTarget(
            applePlatformType, getPrefixedAppleCpu(applePlatformType, appleCpus)
        )

    @Throws(EvalException::class)
    override fun iosSdkVersionFlag(): DottedVersion? {
        return iosSdkVersionFlag
    }

    @Throws(EvalException::class)
    override fun macOsSdkVersionFlag(): DottedVersion? {
        return macOsSdkVersionFlag
    }

    @Throws(EvalException::class)
    override fun tvOsSdkVersionFlag(): DottedVersion? {
        return tvOsSdkVersionFlag
    }

    @Throws(EvalException::class)
    override fun watchOsSdkVersionFlag(): DottedVersion? {
        return watchOsSdkVersionFlag
    }

    @Throws(EvalException::class)
    override fun iosMinimumOsFlag(): DottedVersion? {
        return iosMinimumOsFlag
    }

    @Throws(EvalException::class)
    override fun macOsMinimumOsFlag(): DottedVersion? {
        return macosMinimumOsFlag
    }

    @Throws(EvalException::class)
    override fun tvOsMinimumOsFlag(): DottedVersion? {
        return tvosMinimumOsFlag
    }

    @Throws(EvalException::class)
    override fun watchOsMinimumOsFlag(): DottedVersion? {
        return watchosMinimumOsFlag
    }

    @Throws(EvalException::class)
    override fun shouldPreferMutualXcode(): Boolean {
        return preferMutualXcode
    }

    @Throws(EvalException::class)
    override fun includeXcodeExecRequirementsFlag(): Boolean {
        return includeXcodeExecRequirements
    }

    /**
     * Returns the label of the xcode_config rule to use for resolving the exec system Xcode version.
     */
    @StarlarkConfigurationField(
        name = "xcode_config_label",
        doc = "Returns the target denoted by the value of the --xcode_version_config flag",
        defaultLabel = AppleCommandLineOptions.Companion.DEFAULT_XCODE_VERSION_CONFIG_LABEL,
        defaultInToolRepository = true
    )
    fun getXcodeConfigLabel(): Label {
        return xcodeConfigLabel
    }

    @Throws(Fragment.OutputDirectoriesContext.AddToMnemonicException::class)
    public override fun processForOutputPathMnemonic(ctx: Fragment.OutputDirectoriesContext) {
        val components: MutableList<String?> = ArrayList<String?>()
        if (!appleCpus.appleSplitCpu().isEmpty()) {
            components.add(applePlatformType)
            components.add(appleCpus.appleSplitCpu())

            if (options.getMinimumOsVersion() != null) {
                components.add("min" + options.getMinimumOsVersion())
            }
        }

        if (!components.isEmpty()) {
            ctx.addToMnemonic(Joiner.on('-').join(components))
        }
    }

    override fun equals(obj: Any?): Boolean {
        if (this === obj) {
            return true
        }
        if (obj !is AppleConfiguration) {
            return false
        }
        return this.options == obj.options
    }

    override fun hashCode(): Int {
        return options.hashCode()
    }

    companion object {
        /** Environment variable name for the developer dir of the selected Xcode.  */
        const val DEVELOPER_DIR_ENV_NAME: String = "DEVELOPER_DIR"

        /**
         * Environment variable name for the Xcode version. The value of this environment variable should
         * be set to the version (for example, "7.2") of Xcode to use when invoking part of the apple
         * toolkit in action execution.
         */
        const val XCODE_VERSION_ENV_NAME: String = "XCODE_VERSION_OVERRIDE"

        /**
         * Environment variable name for the apple SDK platform. This should be set for all actions that
         * require an apple SDK. The valid values consist of [ApplePlatform] names.
         */
        const val APPLE_SDK_PLATFORM_ENV_NAME: String = "APPLE_SDK_PLATFORM"

        /** Prefix for simulator environment cpu values  */
        const val SIMULATOR_ENVIRONMENT_CPU_PREFIX: String = "sim_"

        /** Prefix for device environment cpu values  */
        const val DEVICE_ENVIRONMENT_CPU_PREFIX: String = "device_"

        /** Default cpu for iOS builds.  */
        @VisibleForTesting
        val DEFAULT_IOS_CPU: String = if (CPU.getCurrent() == CPU.AARCH64) "sim_arm64" else "x86_64"

        private fun getUnprefixedAppleCpu(applePlatformType: String?, appleCpus: AppleCpus): String {
            // The environment data prefix is removed from the CPU string,
            // - e.g. whether the target CPU is for simulator, device or catalyst.
            //  For older CPUs no environment may be provided.
            var cpu: String = getPrefixedAppleCpu(applePlatformType, appleCpus)
            if (cpu.startsWith(SIMULATOR_ENVIRONMENT_CPU_PREFIX)) {
                cpu = cpu.substring(SIMULATOR_ENVIRONMENT_CPU_PREFIX.length)
            } else if (cpu.startsWith(DEVICE_ENVIRONMENT_CPU_PREFIX)) {
                cpu = cpu.substring(DEVICE_ENVIRONMENT_CPU_PREFIX.length)
            }
            return cpu
        }

        private fun getPrefixedAppleCpu(applePlatformType: String?, appleCpus: AppleCpus): String {
            if (!Strings.isNullOrEmpty(appleCpus.appleSplitCpu())) {
                return appleCpus.appleSplitCpu()
            }
            return when (applePlatformType) {
                PlatformType.IOS -> appleCpus.iosMultiCpus().get(0)
                PlatformType.VISIONOS -> appleCpus.visionosCpus().get(0)
                PlatformType.WATCHOS -> appleCpus.watchosCpus().get(0)
                PlatformType.TVOS -> appleCpus.tvosCpus().get(0)
                PlatformType.MACOS -> appleCpus.macosCpus().get(0)
                else -> throw java.lang.IllegalArgumentException("Unhandled platform type " + applePlatformType)
            }!!
        }
    }
}
