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
package com.google.devtools.build.lib.rules.apple

import com.google.common.base.Ascii
import com.google.common.base.Preconditions
import com.google.common.collect.ImmutableSet
import com.google.devtools.build.lib.concurrent.ThreadSafety
import com.google.devtools.build.lib.packages.BuiltinProvider
import net.starlark.java.eval.Printer
import net.starlark.java.eval.StarlarkSemantics

// LINT.IfChange
// TODO(b/331163027): Remove this duplicate of common/objc/apple_platform.PLATFORM_TYPE
/**
 * An enum that can be used to distinguish between various apple platforms.
 * 
 * 
 * The enum Platform is being migrated to a Starlark struct PLATFORM in
 * builtins_bzl/common/objc/apple_platform.bzl.
 */
@ThreadSafety.Immutable
enum class ApplePlatform(starlarkKey: String, nameInPlist: String, platformType: String, isDevice: Boolean) :
    ApplePlatformApi {
    IOS_DEVICE("ios_device", "iPhoneOS", PlatformType.IOS, true),
    IOS_SIMULATOR("ios_simulator", "iPhoneSimulator", PlatformType.IOS, false),
    MACOS("macos", "MacOSX", PlatformType.MACOS, true),
    TVOS_DEVICE("tvos_device", "AppleTVOS", PlatformType.TVOS, true),
    TVOS_SIMULATOR("tvos_simulator", "AppleTVSimulator", PlatformType.TVOS, false),
    VISIONOS_DEVICE("visionos_device", "XROS", PlatformType.VISIONOS, true),
    VISIONOS_SIMULATOR("visionos_simulator", "XRSimulator", PlatformType.VISIONOS, false),
    WATCHOS_DEVICE("watchos_device", "WatchOS", PlatformType.WATCHOS, true),
    WATCHOS_SIMULATOR("watchos_simulator", "WatchSimulator", PlatformType.WATCHOS, false),
    CATALYST("catalyst", "MacOSX", PlatformType.CATALYST, true);

    val name: String?
    val nameInPlist: String
    val type: String?
    val isDevice: Boolean

    init {
        this.name = starlarkKey
        this.nameInPlist = Preconditions.checkNotNull<String>(nameInPlist)
        this.type = platformType
        this.isDevice = isDevice
    }

    val isImmutable: Boolean
        get() = true // immutable and Starlark-hashable

    val lowerCaseNameInPlist: String
        /**
         * Returns the name of the "platform" as it appears in the plist when it appears in all-lowercase.
         */
        get() = nameInPlist.lowercase()

    override fun repr(printer: Printer, semantics: StarlarkSemantics?) {
        printer.append(Ascii.toLowerCase(toString()))
    }

    /** Exception indicating an unknown or unsupported Apple platform type.  */
    class UnsupportedPlatformTypeException(msg: String?) : Exception(msg)

    // TODO(b/331163027): Remove this duplicate of common/objc/apple_platform.PLATFORM_TYPE
    /**
     * The former enum PlatformType is being migrated to a Starlark struct PLATFORM_TYPE in
     * builtins_bzl/common/objc/apple_platform.bzl. During the migration, PlatformType has been
     * converted to a static class hosting string constants as Java duplicates of
     * apple_platform.PLATFORM_TYPE.
     */
    object PlatformType {
        // implements ApplePlatformTypeApi {
        const val IOS: String = "ios"
        const val VISIONOS: String = "visionos"
        const val WATCHOS: String = "watchos"
        const val TVOS: String = "tvos"
        const val MACOS: String = "macos"
        const val CATALYST: String = "catalyst"
    } // LINT.ThenChange(//src/main/starlark/builtins_bzl/common/objc/apple_platform.bzl)

    companion object {
        private val IOS_SIMULATOR_TARGET_CPUS: ImmutableSet<String?> =
            ImmutableSet.of<String?>("ios_x86_64", "ios_i386", "ios_sim_arm64", "ios_sim_arm64e")
        private val IOS_DEVICE_TARGET_CPUS: ImmutableSet<String?> =
            ImmutableSet.of<String?>("ios_armv6", "ios_arm64", "ios_armv7", "ios_armv7s", "ios_arm64e")
        private val VISIONOS_SIMULATOR_TARGET_CPUS: ImmutableSet<String?> =
            ImmutableSet.of<String?>("visionos_sim_arm64", "visionos_sim_arm64e")
        private val VISIONOS_DEVICE_TARGET_CPUS: ImmutableSet<String?> =
            ImmutableSet.of<String?>("visionos_arm64", "visionos_arm64e")
        private val WATCHOS_SIMULATOR_TARGET_CPUS: ImmutableSet<String?> =
            ImmutableSet.of<String?>("watchos_i386", "watchos_x86_64", "watchos_arm64", "watchos_sim_arm64e")
        private val WATCHOS_DEVICE_TARGET_CPUS: ImmutableSet<String?> = ImmutableSet.of<String?>(
            "watchos_armv7k", "watchos_arm64_32", "watchos_device_arm64", "watchos_device_arm64e"
        )
        private val TVOS_SIMULATOR_TARGET_CPUS: ImmutableSet<String?> =
            ImmutableSet.of<String?>("tvos_x86_64", "tvos_sim_arm64", "tvos_sim_arm64e")
        private val TVOS_DEVICE_TARGET_CPUS: ImmutableSet<String?> =
            ImmutableSet.of<String?>("tvos_arm64", "tvos_arm64e")
        private val CATALYST_TARGET_CPUS: ImmutableSet<String?> = ImmutableSet.of<String?>("catalyst_x86_64")
        private val MACOS_TARGET_CPUS: ImmutableSet<String?> =
            ImmutableSet.of<String?>("darwin_x86_64", "darwin_arm64", "darwin_arm64e")

        private fun forTargetCpuNullable(targetCpu: String?): ApplePlatform? {
            if (IOS_SIMULATOR_TARGET_CPUS.contains(targetCpu)) {
                return ApplePlatform.IOS_SIMULATOR
            } else if (IOS_DEVICE_TARGET_CPUS.contains(targetCpu)) {
                return ApplePlatform.IOS_DEVICE
            } else if (VISIONOS_SIMULATOR_TARGET_CPUS.contains(targetCpu)) {
                return ApplePlatform.VISIONOS_SIMULATOR
            } else if (VISIONOS_DEVICE_TARGET_CPUS.contains(targetCpu)) {
                return ApplePlatform.VISIONOS_DEVICE
            } else if (WATCHOS_SIMULATOR_TARGET_CPUS.contains(targetCpu)) {
                return ApplePlatform.WATCHOS_SIMULATOR
            } else if (WATCHOS_DEVICE_TARGET_CPUS.contains(targetCpu)) {
                return ApplePlatform.WATCHOS_DEVICE
            } else if (TVOS_SIMULATOR_TARGET_CPUS.contains(targetCpu)) {
                return ApplePlatform.TVOS_SIMULATOR
            } else if (TVOS_DEVICE_TARGET_CPUS.contains(targetCpu)) {
                return ApplePlatform.TVOS_DEVICE
            } else if (CATALYST_TARGET_CPUS.contains(targetCpu)) {
                return ApplePlatform.CATALYST
            } else if (MACOS_TARGET_CPUS.contains(targetCpu)) {
                return ApplePlatform.MACOS
            } else {
                return null
            }
        }

        /**
         * Returns the platform cpu string for the given target cpu and platform type.
         * 
         * @param platformType platform type that the given cpu value is implied for
         * @param arch architecture representation, such as 'arm64'
         */
        private fun cpuStringForTarget(platformType: String, arch: String?): String? {
            when (platformType) {
                PlatformType.MACOS -> return String.format("darwin_%s", arch)
                else -> return String.format("%s_%s", platformType.toString(), arch)
            }
        }

        /**
         * Returns the platform for the given target cpu and platform type.
         * 
         * @param platformType platform type that the given cpu value is implied for
         * @param arch architecture representation, such as 'arm64'
         * @throws IllegalArgumentException if there is no valid apple platform for the given target cpu
         */
        fun forTarget(platformType: String, arch: String?): ApplePlatform {
            return forTargetCpu(cpuStringForTarget(platformType, arch))
        }

        /**
         * Returns the platform for the given target cpu.
         * 
         * @param targetCpu cpu value with platform type prefix, such as 'ios_arm64'
         * @throws IllegalArgumentException if there is no valid apple platform for the given target cpu
         */
        fun forTargetCpu(targetCpu: String?): ApplePlatform {
            val platform: ApplePlatform? = forTargetCpuNullable(targetCpu)
            if (platform != null) {
                return platform
            } else {
                throw IllegalArgumentException(
                    "No supported apple platform registered for target cpu " + targetCpu
                )
            }
        }

        val starlarkStruct: StructImpl
            /** Returns a Starlark struct that contains the instances of this enum.  */
            get() {
                val constructor: Provider =
                    object : BuiltinProvider<StructImpl?>("platforms", StructImpl::class.java) {}
                val fields: HashMap<String?, Any?> = HashMap<String?, Any?>()
                for (type in ApplePlatform.entries) {
                    fields.put(type.starlarkKey, type)
                }
                return StarlarkInfo.create(constructor, fields)
            }
    }
}
