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
package com.google.devtools.build.lib.analysis.config

import com.google.devtools.build.lib.analysis.PlatformOptions

/**
 * Machinery for computing the output path mnemonic given the target options, constructed fragments
 * for those target options, and current baseline options (which will be null in legacy mode or odd
 * parts of the test infra).
 */
@com.google.common.annotations.VisibleForTesting
object OutputPathMnemonicComputer {
    // The length of the hash of the config tacked onto the end of the output path.
    // Limited for ergonomics and MAX_PATH reasons.
    private const val HASH_LENGTH = 12

    /**
     * Compute and return the output path mnemonic.
     * 
     * 
     * The general form is [cpu]-[compilation_mode]-[platform_suffix?]-...-[-ST-hash?] where ... is
     * any additions requested by the [Fragment] via [ ] during calls to [ ].
     * 
     * 
     * platform_suffix is omitted if empty.
     * 
     * 
     * The exact ST-hash used depends on baselineOptions. The hash includes all options that are
     * different between buildOptions and baselineOptions but were also not excluded from the output
     * path by a call to [Fragment.OutputDirectoriesContext.markAsExplicitInOutputPathFor]
     */
    @Throws(InvalidMnemonicException::class)
    fun computeMnemonic(
        buildOptions: BuildOptions,
        baselineOptions: BuildOptions?,
        fragments: com.google.common.collect.ImmutableSortedMap<java.lang.Class<out com.google.devtools.build.lib.analysis.config.Fragment?>?, com.google.devtools.build.lib.analysis.config.Fragment?>
    ): String {
        val coreOptions: CoreOptions? = buildOptions.get<T?>(CoreOptions::class.java)

        if (buildOptions.hasNoConfig()) {
            // Historically, the noconfig output path mnemonic had the compilation mode.
            return coreOptions.getCompilationMode().toString() + "-noconfig" // See NoConfigTransition.
        }

        val platformOptions: PlatformOptions? = buildOptions.get<T?>(PlatformOptions::class.java)

        val ctx = MnemonicContext(baselineOptions)

        handlePlatformCpuDescriptor(ctx, coreOptions, platformOptions)

        ctx.checkedAddToMnemonic(coreOptions.getCompilationMode().toString(), "Compilation mode")
        ctx.markAsExplicitInOutputPathFor("compilation_mode")

        if (!com.google.common.base.Strings.isNullOrEmpty(coreOptions.getPlatformSuffix())) {
            ctx.checkedAddToMnemonic(coreOptions.getPlatformSuffix(), "Platform suffix")
        }
        ctx.markAsExplicitInOutputPathFor("platform_suffix")

        for (entry in fragments.entrySet()) {
            ctx.consume(entry.getValue())
        }

        val explicitInOutputPathOptions: com.google.common.collect.ImmutableSet<String?> =
            ctx.getExplicitInOutputPathOptions()

        // Sanity check that every listed option in explicitInOutputPathOptions actually exists.
        // TODO(blaze-configurability-team): Should technically be unnecessary to do this every time as
        // all the calls to markAsExplicitInOutputPathFor should be constant for a given release.
        // Instead, could do this when a specific flag is supplied and just check in test code.
        // Alternatively, just do a better job of caching the call to OptionInfo.buildMapFrom as only
        // that call is the expensive part.
        val optionInfoMap: com.google.common.collect.ImmutableMap<String?, OptionInfo?> =
            OptionInfo.buildMapFrom(buildOptions)
        val missingOptions: com.google.common.collect.ImmutableSet<String?> =
            explicitInOutputPathOptions.stream()
                .filter(java.util.function.Predicate { optionName: String? -> !optionInfoMap.containsKey(optionName) })
                .collect(com.google.common.collect.ImmutableSet.toImmutableSet<String?>())
        check(missingOptions.isEmpty()) {
            ("Internal error: Options registered for special output handling that do not exist: "
                    + missingOptions)
        }

        ctx.checkedAddToMnemonic(
            computeNameFragmentWithDiff(
                buildOptions,
                com.google.common.base.Verify.verifyNotNull<BuildOptions?>(baselineOptions),
                explicitInOutputPathOptions
            ),
            "Transition directory name fragment"
        )
        return ctx.getMnemonic()
    }

    @Throws(InvalidMnemonicException::class)
    private fun handlePlatformCpuDescriptor(
        ctx: MnemonicContext, coreOptions: CoreOptions, platformOptions: PlatformOptions?
    ) {
        if (platformOptions == null
            || !coreOptions.usePlatformInOutputDir(platformOptions.computeTargetPlatform())
        ) {
            ctx.checkedAddToMnemonic(coreOptions.getCpu(), "CPU/Platform descriptor")
            ctx.markAsExplicitInOutputPathFor("cpu")
            return
        }

        if (platformOptions.getPlatforms() != null && platformOptions.getPlatforms().size() > 1) {
            ctx.checkedAddToMnemonic("multi-platform", "CPU/Platform descriptor")
            // Intentionally not marking anything as explicit in output path so ST-hash used if needed.
            return
        }

        ctx.checkedAddToMnemonic(
            computePlatformName(platformOptions.computeTargetPlatform(), coreOptions),
            "CPU/Platform descriptor"
        )
        ctx.markAsExplicitInOutputPathFor("platforms")
    }

    private fun computePlatformName(platform: Label, options: CoreOptions): String? {
        val overridePlatformName: java.util.Optional<String?> = options.getPlatformCpuNameOverride(platform)
        if (overridePlatformName.isPresent()) {
            return overridePlatformName.get()
        }

        // Handle legacy heuristic if enabled.
        // Note that it is known this heuristic is not necessarily complete.
        if (options.getUsePlatformsInOutputDirLegacyHeuristic()) {
            // Only use non-default platforms.

            if (!PlatformOptions.platformIsDefault(platform)) {
                return platform.getName()
            }
            // Fall back to using the CPU.
            return options.getCpu()
        }
        // As a last resort use hashCode of the unambiguous form of the label.
        return java.lang.String.format("platform-%X", platform.getUnambiguousCanonicalForm().hashCode())
    }

    /**
     * Compute the hash for the new BuildOptions based on the names and values of all options (both
     * native and Starlark) that are different from some supplied baseline configuration.
     */
    @com.google.common.annotations.VisibleForTesting
    fun computeNameFragmentWithDiff(
        toOptions: BuildOptions,
        baselineOptions: BuildOptions?,
        explicitInOutputPathOptions: com.google.common.collect.ImmutableSet<String?>
    ): String {
        // Quick short-circuit for trivial case.
        if (toOptions == baselineOptions) {
            return ""
        }

        // TODO(blaze-configurability-team): As a mild performance update, getFirst already includes
        //   details of the corresponding option. Could incorporate this instead of hashChosenOptions
        //   regenerating the OptionDefinitions and values.
        val diff: OptionsDiff = OptionsDiff.diff(toOptions, baselineOptions)
        // Note: getFirst only excludes options trimmed between baselineOptions to toOptions and this is
        //   considered OK as a given Rule should not be being built with options of different
        //   trimmings. See longform note in {@link ConfiguredTargetKey} for details.
        val chosenNativeOptions: com.google.common.collect.ImmutableSet<String?> =
            diff.getFirst().keySet().stream()
                .map(OptionDefinition::getOptionName)
                .filter({ optionName -> !explicitInOutputPathOptions.contains(optionName) })
                .collect(com.google.common.collect.ImmutableSet.toImmutableSet<E?>())
        // Note: getChangedStarlarkOptions includes all changed options, added options and removed
        //   options between baselineOptions and toOptions. This is necessary since there is no current
        //   notion of trimming a Starlark option: 'null' or non-existent justs means set to default.
        val chosenStarlarkOptions: com.google.common.collect.ImmutableSet<String?> =
            diff.getChangedStarlarkOptions().stream().map(Label::toString)
                .collect(com.google.common.collect.ImmutableSet.toImmutableSet<E?>())
        return hashChosenOptions(toOptions, chosenNativeOptions, chosenStarlarkOptions)
    }

    /**
     * Compute a hash of the given BuildOptions by hashing only the options referenced in both
     * chosenNative and chosenStarlark. The order of the chosen order does not matter (as this
     * function will effectively sort them into a canonical order) and the pre-hash for each option
     * will be of the form (//command_line_option:[native option]|[Starlark option label])=[value].
     * 
     * 
     * If a supplied native option does not exist, it is skipped (as it is presumed non-existence
     * is due to trimming).
     * 
     * 
     * If a supplied Starlark option does exist, the pre-hash will be [Starlark option label]@null
     * (as it is presumed non-existence is due to being set to default value).
     */
    private fun hashChosenOptions(
        toOptions: BuildOptions, chosenNative: Iterable<String?>, chosenStarlark: Iterable<String?>
    ): String {
        // TODO(blaze-configurability-team): A mild performance optimization would have this be global.
        val optionInfoMap: com.google.common.collect.ImmutableMap<String?, OptionInfo?> =
            OptionInfo.buildMapFrom(toOptions)

        // Note that the TreeMap guarantees a stable ordering of keys and thus
        // it is okay if chosenNative or chosenStarlark do not have a stable iteration order
        val toHash: TreeMap<String?, Any?> = TreeMap<String?, Any?>()
        for (nativeOptionName in chosenNative) {
            val optionInfo: OptionInfo? = optionInfoMap.get(nativeOptionName)
            if (optionInfo == null) {
                // This can occur if toOptions has been trimmed but the supplied chosen native options
                // includes that trimmed options.
                // (e.g. legacy naming mode, using --trim_test_configuration and --test_arg transition).
                continue
            }
            val fragmentOptions: FragmentOptions? =
                toOptions.get<T?>(optionInfoMap.get(nativeOptionName).getOptionClass())
            val value: Any? = optionInfo.getDefinition().getValue(fragmentOptions)
            // TODO(blaze-configurability-team): The commandline option is legacy and can be removed
            //   after fixing up all the associated tests.
            toHash.put("//command_line_option:" + nativeOptionName, value)
        }
        for (starlarkOptionName in chosenStarlark) {
            val value: Any? =
                toOptions.getStarlarkOptions().get(Label.parseCanonicalUnchecked(starlarkOptionName))
            toHash.put(starlarkOptionName, value)
        }

        if (toHash.isEmpty()) {
            return ""
        } else {
            val hashStrs: com.google.common.collect.ImmutableList.Builder<String?> =
                com.google.common.collect.ImmutableList.builderWithExpectedSize<String?>(toHash.size())
            for (singleOptionAndValue in toHash.entrySet()) {
                val value: Any? = singleOptionAndValue.getValue()
                if (value != null) {
                    hashStrs.add(singleOptionAndValue.getKey() + "=" + value)
                } else {
                    // Avoid using =null to different from value being the non-null String "null"
                    hashStrs.add(singleOptionAndValue.getKey() + "@null")
                }
            }
            return transitionDirectoryNameFragment(hashStrs.build())
        }
    }

    @com.google.common.annotations.VisibleForTesting
    fun transitionDirectoryNameFragment(opts: Iterable<String?>): String {
        val fp: Fingerprint = Fingerprint()
        for (opt in opts) {
            fp.addString(opt)
        }
        // Shorten the hash to HASH_LENGTH characters. This should provide sufficient collision
        // avoidance (that is, we don't expect anyone to experience a collision ever).
        // Shortening the hash is important for Windows paths that tend to be short.
        val suffix: String = fp.hexDigestAndReset().substring(0, HASH_LENGTH)
        return "ST-" + suffix
    }

    /** Indicates a failure to construct the mnemonic for an output directory.  */
    class InvalidMnemonicException internal constructor(message: String?, e: java.lang.Exception) :
        InvalidConfigurationException(
            message + " is invalid as part of a path: " + e.getMessage(),
            Code.INVALID_OUTPUT_DIRECTORY_MNEMONIC
        )

    /**
     * Create a fresh context to pass to [Fragment.processForOutputPathMnemonic]
     * 
     * 
     * Needs to be fresh since want new state tracking the current mnemonic and explicit in output
     * path option exclusions.
     * 
     * 
     * Note that this class roughly has two sets of methods: 1. The overrides of
     * Fragment.OutputDirectoriesContext 2. The new methods used by OutputPathMnemonicComputer to make
     * its own additions to the mnemonic and extraction of the information.
     */
    private class MnemonicContext(baselineOptions: BuildOptions?) : OutputDirectoriesContext {
        private val baselineOptions: BuildOptions?
        private val mnemonicBuilder: java.lang.StringBuilder
        private val explicitInOutputPathBuilder: com.google.common.collect.ImmutableSet.Builder<String?>

        init {
            this.baselineOptions = baselineOptions
            this.mnemonicBuilder = java.lang.StringBuilder()
            this.explicitInOutputPathBuilder = com.google.common.collect.ImmutableSet.builder<String?>()
        }

        // Implementations for FragmentOptions to use:
        /* If available, get the baseline version of some FragmentOptions */
        override fun <T : FragmentOptions?> getBaseline(optionsClass: java.lang.Class<T?>?): T? {
            if (baselineOptions == null) {
                return null
            }
            return baselineOptions.get<T?>(optionsClass)
        }

        /* Adds given String to the explicit part of the output path. */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        @Throws(AddToMnemonicException::class)
        override fun addToMnemonic(value: String?): OutputDirectoriesContext {
            if (com.google.common.base.Strings.isNullOrEmpty(value)) {
                return this
            }
            try {
                // Allowing for path separators (e.g. /) would be a disaster.
                PathFragment.checkSeparators(value)
                // Want dashes in-between additions.
                // (Note that length of a StringBuilder is very cheap to check so this performs fine.)
                if (mnemonicBuilder.length() > 0) {
                    mnemonicBuilder.append("-")
                }
                mnemonicBuilder.append(value)
            } catch (e: InvalidBaseNameException) {
                throw AddToMnemonicException(value, e)
            }
            return this
        }

        /** See docs at [Fragment.OutputDirectoriesContext.markAsExplicitInOutputPathFor].  */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        override fun markAsExplicitInOutputPathFor(optionName: String?): OutputDirectoriesContext {
            explicitInOutputPathBuilder.add(optionName)
            return this
        }

        // Interface and Implementations for BuildConfigurationFunction to use:
        @Throws(InvalidMnemonicException::class)
        fun consume(fragment: com.google.devtools.build.lib.analysis.config.Fragment) {
            try {
                fragment.processForOutputPathMnemonic(this)
            } catch (e: AddToMnemonicException) {
                throw InvalidMnemonicException(
                    java.lang.String.format(
                        "Output directory name '%s' specified by %s",
                        e.badValue, fragment.getClass().getSimpleName()
                    ),
                    e.tunneledException
                )
            }
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        @Throws(InvalidMnemonicException::class)
        fun checkedAddToMnemonic(
            value: String?, valueCtx: String?
        ): OutputDirectoriesContext {
            try {
                addToMnemonic(value)
            } catch (e: AddToMnemonicException) {
                throw InvalidMnemonicException(
                    java.lang.String.format("%s '%s'", valueCtx, e.badValue), e.tunneledException
                )
            }
            return this
        }

        fun getMnemonic(): String {
            return mnemonicBuilder.toString()
        }

        fun getExplicitInOutputPathOptions(): com.google.common.collect.ImmutableSet<String?> {
            return explicitInOutputPathBuilder.build()
        }
    }
}
