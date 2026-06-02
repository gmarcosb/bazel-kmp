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
package com.google.devtools.build.lib.skyframe.config

import com.google.devtools.build.lib.server.FailureDetails.TargetPatterns.Code.DEPENDENCY_NOT_FOUND

/**
 * Converts a list of command-line flags (like `--compilation_mode=dbg` or `--//custom/starlark:flag=foo`) into a [NativeAndStarlarkFlags] instance. This is intended
 * as preparation for using the flags to create or update a build configuration in Bazel.
 */
class ParsedFlagsFunction(optionsClasses: com.google.common.collect.ImmutableSet<java.lang.Class<out FragmentOptions?>?>?) :
    SkyFunction {
    private val optionsClasses: com.google.common.collect.ImmutableSet<java.lang.Class<out FragmentOptions?>?>?

    init {
        this.optionsClasses = optionsClasses
    }

    @Throws(java.lang.InterruptedException::class, ParsedFlagsFunctionException::class)
    override fun compute(skyKey: SkyKey, env: SkyFunction.Environment): SkyValue? {
        val key: com.google.devtools.build.lib.skyframe.config.ParsedFlagsValue.Key =
            skyKey.argument() as com.google.devtools.build.lib.skyframe.config.ParsedFlagsValue.Key

        val nativeFlags: com.google.common.collect.ImmutableList.Builder<String?> =
            com.google.common.collect.ImmutableList.builder<String?>()
        val starlarkFlags: com.google.common.collect.ImmutableList.Builder<String?> =
            com.google.common.collect.ImmutableList.builder<String?>()
        val flagAliasMappings: com.google.common.collect.ImmutableMap<String?, Label?> = key.flagAliasMappings()
        for (flagSetting in key.rawFlags()) {
            var flagSetting: String = flagSetting
            if (!flagSetting.startsWith("--")) {
                // This is either something like "-c" or an invalid setting. Let options parsing handle it.
                nativeFlags.add(flagSetting)
                continue
            }
            val flagName: String?
            var flagValue = ""
            val delimiterIndex: Int = flagSetting.indexOf("=")
            var noPrefix = false
            if (delimiterIndex != -1) {
                flagName = flagSetting.substring(2, delimiterIndex) // --flag=value
                flagValue = flagSetting.substring(delimiterIndex + 1)
            } else if (flagSetting.startsWith("--no")) {
                flagName = flagSetting.substring(4) // --no<flag>
                noPrefix = true
            } else {
                flagName = flagSetting.substring(2) // --<flag>
            }
            // If --flag_alias=foo=//bar and we see --foo=1, use the canonical setting --//bar=1.
            val actualFlag: Label? = flagAliasMappings.get(flagName)
            if (actualFlag != null) {
                flagSetting =
                    "--%s%s%s"
                        .formatted(
                            if (noPrefix) "no" else "",
                            actualFlag.getUnambiguousCanonicalForm(),
                            if (delimiterIndex == -1) "" else "=" + flagValue
                        )
            }
            if (com.google.devtools.common.options.OptionsParser.STARLARK_SKIPPED_PREFIXES.stream()
                    .noneMatch(java.util.function.Predicate { prefix: String? -> flagSetting.startsWith(prefix) })
            ) {
                nativeFlags.add(flagSetting)
            } else {
                starlarkFlags.add(flagSetting)
            }
        }
        // The StarlarkOptionsParser needs a native options parser to handle some forms of value
        // conversion and as a place to inject the flag values.
        // TODO: https://github.com/bazelbuild/bazel/issues/22365 - Clean this up as part of a general
        // rewrite.
        val fakeNativeParser: com.google.devtools.common.options.OptionsParser =
            com.google.devtools.common.options.OptionsParser.builder().withConversionContext(key.packageContext())
                .build()
        val starlarkFlagParser: StarlarkOptionsParser =
            StarlarkOptionsParser.builder()
                .buildSettingLoader(
                    com.google.devtools.build.lib.skyframe.config.ParsedFlagsFunction.SkyframeTargetLoader(
                        env,
                        key.packageContext()
                    )
                )
                .nativeOptionsParser(fakeNativeParser)
                .includeDefaultValues(key.includeDefaultValues())
                .build()
        try {
            if (!starlarkFlagParser.parseGivenArgs(starlarkFlags.build())) {
                return null
            }
        } catch (e: com.google.devtools.common.options.OptionsParsingException) {
            throw ParsedFlagsFunctionException(e)
        }
        val flags: com.google.devtools.build.lib.skyframe.config.NativeAndStarlarkFlags.Builder =
            NativeAndStarlarkFlags.Companion.builder()
                .nativeFlags(nativeFlags.build())
                .starlarkFlags(starlarkFlagParser.getStarlarkOptions())
                .starlarkOptionAllowingMultiple(starlarkFlagParser.getStarlarkOptionsAllowingMultiple())
                .scopesAttributes(starlarkFlagParser.getScopesAttributes())
                .optionsClasses(optionsClasses)
                .repoMapping(key.packageContext().repoMapping())

        if (key.includeDefaultValues()) {
            flags.starlarkFlagDefaults(starlarkFlagParser.getDefaultValues())
        }

        try {
            return ParsedFlagsValue.Companion.parseAndCreate(flags.build())
        } catch (e: com.google.devtools.common.options.OptionsParsingException) {
            throw ParsedFlagsFunctionException(e)
        }
    }

    /**
     * Lets [StarlarkOptionsParser] convert flag names to [Target]s through a Skyframe
     * [PackageValue] lookup.
     */
    private class SkyframeTargetLoader
        (env: SkyFunction.Environment, packageContext: PackageContext?) : StarlarkOptionsParser.BuildSettingLoader {
        private val env: SkyFunction.Environment
        private val packageContext: PackageContext?

        init {
            this.env = env
            this.packageContext = packageContext
        }

        @Throws(java.lang.InterruptedException::class, TargetParsingException::class)
        public override fun loadBuildSetting(name: String?): Target? {
            val asLabel: Label
            try {
                asLabel = Label.parseWithPackageContext(name, packageContext)
            } catch (e: LabelSyntaxException) {
                throw java.lang.IllegalArgumentException(e)
            }
            try {
                val pkgKey: SkyKey? = asLabel.getPackageIdentifier()
                val pkg: PackageValue? =
                    env.getValueOrThrow<E?>(pkgKey, NoSuchPackageException::class.java) as PackageValue?
                if (pkg == null) {
                    return null
                }
                return pkg.getPackage().getTarget(asLabel.name)
            } catch (e: NoSuchPackageException) {
                throw TargetParsingException(
                    java.lang.String.format("Failed to load %s", name), e, DEPENDENCY_NOT_FOUND
                )
            } catch (e: NoSuchTargetException) {
                throw TargetParsingException(
                    java.lang.String.format("Failed to load %s", name), e, DEPENDENCY_NOT_FOUND
                )
            }
        }
    }

    private class ParsedFlagsFunctionException(e: com.google.devtools.common.options.OptionsParsingException?) :
        SkyFunctionException(e, Transience.PERSISTENT)
}
