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
package com.google.devtools.build.lib.skyframe.toolchains

import com.google.devtools.build.lib.actions.ActionConflictException

/** Helper class that looks up [PlatformInfo] data.  */
object PlatformLookupUtil {
    @Throws(java.lang.InterruptedException::class, InvalidPlatformException::class)
    fun getPlatformInfo(
        platformKeys: com.google.common.collect.ImmutableList<ConfiguredTargetKey>, env: SkyFunction.Environment
    ): MutableMap<ConfiguredTargetKey?, PlatformInfo?>? {
        validatePlatformKeys(platformKeys, env)
        if (env.valuesMissing()) {
            return null
        }

        val values: SkyframeLookupResult = env.getValuesAndExceptions(platformKeys)
        val valuesMissing: Boolean = env.valuesMissing()
        val platforms: MutableMap<ConfiguredTargetKey?, PlatformInfo?>? =
            if (valuesMissing) null else HashMap<ConfiguredTargetKey?, PlatformInfo?>()
        for (key in platformKeys) {
            val platformInfo: PlatformInfo? = findPlatformInfo(key, values)
            if (!valuesMissing && platformInfo != null) {
                platforms!!.put(key, platformInfo)
            }
        }
        if (valuesMissing) {
            return null
        }

        return platforms
    }

    /** Validate that all keys are for actual platform targets.  */
    @Throws(java.lang.InterruptedException::class, InvalidPlatformException::class)
    private fun validatePlatformKeys(
        platformKeys: com.google.common.collect.ImmutableList<ConfiguredTargetKey>, env: SkyFunction.Environment
    ) {
        // Load the packages. This should already be in Skyframe and thus not require a restart.
        val packageKeys: com.google.common.collect.ImmutableSet<PackageIdentifier> =
            platformKeys.stream()
                .map<Any?>(ConfiguredTargetKey::getLabel)
                .map<Any?>(Label::getPackageIdentifier)
                .collect(com.google.common.collect.ImmutableSet.toImmutableSet<Any?>())

        val values: SkyframeLookupResult = env.getValuesAndExceptions(packageKeys)
        val valuesMissing: Boolean = env.valuesMissing()
        val packages: MutableMap<PackageIdentifier?, Package?>? =
            if (valuesMissing) null else HashMap<PackageIdentifier?, Package?>()
        for (packageKey in packageKeys) {
            try {
                val packageValue: PackageValue? =
                    values.getOrThrow<E?>(packageKey, NoSuchPackageException::class.java) as PackageValue?
                if (!valuesMissing && packageValue != null) {
                    packages!!.put(packageKey, packageValue.getPackage())
                }
            } catch (e: NoSuchPackageException) {
                throw InvalidPlatformException(e)
            }
        }
        if (env.valuesMissing()) {
            if (valuesMissing != env.valuesMissing()) {
                BugReport.sendBugReport(
                    java.lang.IllegalStateException(
                        "Some value from " + packageKeys + " was missing, this should never happen"
                    )
                )
            }
            return
        }

        // Now check each platform.
        for (platformKey in platformKeys) {
            try {
                val platformLabel: Label = platformKey.getLabel()
                val target: Target =
                    packages!!.get(platformLabel.getPackageIdentifier()).getTarget(platformLabel.name)
                if (!hasPlatformInfo(target)) {
                    // validation failure
                    throw InvalidPlatformException(platformLabel)
                }
            } catch (e: NoSuchTargetException) {
                throw InvalidPlatformException(e)
            }
        }
    }

    /**
     * Returns the [PlatformInfo] provider from the [ConfiguredTarget] in the [ ], or `null` if the [ConfiguredTarget] is not present. If the
     * [ConfiguredTarget] does not have a [PlatformInfo] provider, a [ ] is thrown.
     */
    @Throws(InvalidPlatformException::class)
    private fun findPlatformInfo(key: ConfiguredTargetKey, values: SkyframeLookupResult): PlatformInfo? {
        try {
            val ctv: ConfiguredTargetValue? =
                values.getOrThrow<E1?, E2?, E3?>(
                    key,
                    ConfiguredValueCreationException::class.java,
                    NoSuchThingException::class.java,
                    ActionConflictException::class.java
                ) as ConfiguredTargetValue?
            if (ctv == null) {
                return null
            }

            val configuredTarget: ConfiguredTarget = ctv.getConfiguredTarget()
            val platformInfo: PlatformInfo = PlatformProviderUtils.platform(configuredTarget)
            if (platformInfo == null) {
                throw InvalidPlatformException(configuredTarget.getLabel())
            }

            return platformInfo
        } catch (e: ConfiguredValueCreationException) {
            throw InvalidPlatformException(key.getLabel(), e)
        } catch (e: NoSuchThingException) {
            throw InvalidPlatformException(e)
        } catch (e: ActionConflictException) {
            throw InvalidPlatformException(key.getLabel(), e)
        }
    }

    fun hasPlatformInfo(target: Target): Boolean {
        val rule: Rule? = target.getAssociatedRule()
        // If the rule uses toolchain resolution, it can't be used as a target or exec platform.
        if (rule == null) {
            return false
        }
        if (rule.useToolchainResolution()) {
            return false
        }

        return rule.getRuleClassObject()
            .getAdvertisedProviders()
            .advertises(PlatformInfo.PROVIDER.id())
    }

    /** Exception used when a platform label is not a valid platform.  */
    class InvalidPlatformException : ToolchainException {
        constructor(label: Label?) : super(formatError(label, DEFAULT_ERROR))

        constructor(label: Label?, e: ConfiguredValueCreationException?) : super(formatError(label, DEFAULT_ERROR), e)

        constructor(e: NoSuchThingException?) : super(e)

        constructor(label: Label?, e: ActionConflictException?) : super(formatError(label, DEFAULT_ERROR), e)

        val detailedCode: Code
            get() = Code.INVALID_PLATFORM_VALUE

        companion object {
            private const val DEFAULT_ERROR = "does not provide PlatformInfo"

            private fun formatError(label: Label?, error: String?): String? {
                return java.lang.String.format("Target %s was referenced as a platform, but %s", label, error)
            }
        }
    }
}
