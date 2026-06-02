// Copyright 2024 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.analysis.platform

import com.google.devtools.build.lib.analysis.ConfiguredTarget

/**
 * Validates that a [Label] is a platform, then requests its [ConfiguredTargetKey].
 * Extracts the [PlatformInfo] from the analyzed configured target and parses any [ ][PlatformInfo.flags].
 */
class PlatformFunction : SkyFunction {
    @Throws(PlatformFunctionException::class, java.lang.InterruptedException::class)
    override fun compute(skyKey: SkyKey, env: SkyFunction.Environment): PlatformValue? {
        val params: com.google.devtools.build.lib.analysis.platform.PlatformValue.Key =
            skyKey.argument() as com.google.devtools.build.lib.analysis.platform.PlatformValue.Key
        val platformLabel: com.google.devtools.build.lib.cmdline.Label = params.label()
        val pkgId: PackageIdentifier = platformLabel.getPackageIdentifier()

        // Load the Package first to verify the Target. The ConfiguredTarget should not be loaded until
        // after verification. See https://github.com/bazelbuild/bazel/pull/10307.
        //
        // In distributed analysis, these packages will be duplicated across shards.
        val target: com.google.devtools.build.lib.packages.Target
        try {
            val pkgValue: PackageValue? =
                env.getValueOrThrow<NoSuchPackageException?>(pkgId, NoSuchPackageException::class.java) as PackageValue?
            if (pkgValue == null) {
                return null
            }
            target = pkgValue.getPackage().getTarget(platformLabel.getName())
        } catch (e: NoSuchPackageException) {
            throw PlatformFunctionException(InvalidPlatformException(e))
        } catch (e: NoSuchTargetException) {
            throw PlatformFunctionException(InvalidPlatformException(e))
        }

        if (!PlatformLookupUtil.hasPlatformInfo(target)) {
            throw PlatformFunctionException(InvalidPlatformException(platformLabel))
        }

        val configuredTarget: ConfiguredTarget
        try {
            val configuredTargetValue: ConfiguredTargetValue? =
                env.getValueOrThrow<E?>(
                    configuredTargetDep(platformLabel), ConfiguredValueCreationException::class.java
                ) as ConfiguredTargetValue?
            if (configuredTargetValue == null) {
                return null
            }
            configuredTarget = configuredTargetValue.getConfiguredTarget()
        } catch (e: ConfiguredValueCreationException) {
            throw PlatformFunctionException(InvalidPlatformException(platformLabel, e))
        }

        val platformInfo: com.google.devtools.build.lib.analysis.platform.PlatformInfo? =
            PlatformProviderUtils.platform(configuredTarget)
        if (platformInfo == null) {
            throw PlatformFunctionException(
                InvalidPlatformException(configuredTarget.getLabel())
            )
        }

        if (platformInfo.flags().isEmpty()) {
            return PlatformValue.Companion.noFlags(platformInfo)
        }

        val repoMappingValue: RepositoryMappingValue? =
            env.getValue(RepositoryMappingValue.key(platformLabel.getRepository())) as RepositoryMappingValue?
        if (repoMappingValue == null) {
            return null
        }

        val parsedFlagsKey: ParsedFlagsValue.Key? =
            ParsedFlagsValue.Key.create(
                platformInfo.flags(),
                PackageContext.of(
                    pkgId,
                    repoMappingValue.repositoryMapping
                ),  // Include default values so that any flags explicitly reset to the default are kept.
                /* includeDefaultValues= */
                true,
                params.flagAliasMappings()
            )
        val parsedFlagsValue: ParsedFlagsValue? = env.getValue(parsedFlagsKey) as ParsedFlagsValue?
        if (parsedFlagsValue == null) {
            return null
        }

        return PlatformValue.Companion.withFlags(platformInfo, parsedFlagsValue)
    }

    private class PlatformFunctionException(cause: InvalidPlatformException?) :
        SkyFunctionException(cause, Transience.PERSISTENT)

    companion object {
        /** Returns the [ConfiguredTargetKey] requested when evaluating the given platform.  */
        fun configuredTargetDep(platformLabel: com.google.devtools.build.lib.cmdline.Label?): ConfiguredTargetKey? {
            // Platforms do not rely on the configuration. Use a dummy blank configuration to reduce the
            // number of skyframe nodes created.
            return ConfiguredTargetKey.builder()
                .setLabel(platformLabel)
                .setConfigurationKey(BuildConfigurationKey.create(CommonOptions.EMPTY_OPTIONS))
                .build()
        }
    }
}
