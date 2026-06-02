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
package com.google.devtools.build.lib.analysis

import com.google.devtools.build.lib.analysis.config.BuildOptions

/** A configuration fragment describing the current platform configuration.  */
@com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable
@RequiresOptions(options = [PlatformOptions::class])
class PlatformConfiguration(platformOptions: PlatformOptions) : Fragment(), PlatformConfigurationApi {
    private val hostPlatform: com.google.devtools.build.lib.cmdline.Label?
    private val extraExecutionPlatforms: com.google.common.collect.ImmutableList<String?>
    private val targetPlatform: com.google.devtools.build.lib.cmdline.Label
    private val extraToolchains: com.google.common.collect.ImmutableList<String?>
    private val toolchainResolutionDebugRegexFilter: com.google.devtools.build.lib.util.RegexFilter

    constructor(options: BuildOptions) : this(options.get(PlatformOptions::class.java))

    init {
        this.hostPlatform = platformOptions.getHostPlatform()
        this.extraExecutionPlatforms =
            com.google.common.collect.ImmutableList.copyOf<String?>(platformOptions.getExtraExecutionPlatforms())
        this.targetPlatform = platformOptions.computeTargetPlatform()
        this.extraToolchains =
            com.google.common.collect.ImmutableList.copyOf<String?>(platformOptions.getExtraToolchains())
        this.toolchainResolutionDebugRegexFilter = platformOptions.getToolchainResolutionDebug()
    }

    public override fun reportInvalidOptions(
        reporter: com.google.devtools.build.lib.events.EventHandler,
        buildOptions: BuildOptions
    ) {
        val platformOptions: PlatformOptions = buildOptions.get(PlatformOptions::class.java)
        // TODO(https://github.com/bazelbuild/bazel/issues/6519): Implement true multiplatform builds.
        if (platformOptions.getPlatforms().size > 1) {
            reporter.handle(
                com.google.devtools.build.lib.events.Event.warn(
                    String.format(
                        "--platforms only supports a single target platform: using the first option %s",
                        this.targetPlatform
                    )
                )
            )
        }
    }

    override fun getHostPlatform(): com.google.devtools.build.lib.cmdline.Label? {
        return hostPlatform
    }

    /**
     * Target patterns that select additional platforms that will be made available for action
     * execution.
     */
    fun getExtraExecutionPlatforms(): com.google.common.collect.ImmutableList<String?> {
        return extraExecutionPlatforms
    }

    /**
     * Returns the single target platform used in this configuration. The flag is multi-valued for
     * future handling of multiple target platforms but any given configuration should only be
     * concerned with a single target platform.
     */
    override fun getTargetPlatform(): com.google.devtools.build.lib.cmdline.Label {
        return targetPlatform
    }

    val targetPlatforms: com.google.common.collect.ImmutableList<com.google.devtools.build.lib.cmdline.Label?>
        get() = com.google.common.collect.ImmutableList.of<com.google.devtools.build.lib.cmdline.Label?>(targetPlatform)

    /**
     * Target patterns that select additional toolchains that will be considered during toolchain
     * resolution.
     */
    fun getExtraToolchains(): com.google.common.collect.ImmutableList<String?> {
        return extraToolchains
    }

    /**
     * Returns true if toolchain resolution debug info should be printed for this label, which could
     * be a toolchain type or a specific target.
     */
    fun debugToolchainResolution(label: com.google.devtools.build.lib.cmdline.Label): Boolean {
        return debugToolchainResolution(
            com.google.common.collect.ImmutableList.of<com.google.devtools.build.lib.cmdline.Label?>(
                label
            )
        )
    }

    /**
     * Returns true if toolchain resolution debug info should be printed for any of these labels,
     * which could be either toolchain types or specific targets.
     */
    fun debugToolchainResolution(labels: MutableCollection<com.google.devtools.build.lib.cmdline.Label?>): Boolean {
        if (labels.isEmpty()) {
            // Check an empty string, in case the filter is .*
            return this.toolchainResolutionDebugRegexFilter.test("")
        }
        return labels.stream()
            .map<String?> { obj: com.google.devtools.build.lib.cmdline.Label? -> obj.getCanonicalForm() }
            .anyMatch(this.toolchainResolutionDebugRegexFilter)
    }
}
