// Copyright 2019 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.actions.FileValue

/**
 * Function that reads the contents of a mapping file specified in `--platform_mappings` and
 * parses them for use in a [PlatformMappingValue].
 * 
 * 
 * Note that this class only parses the mapping-file specific format, parsing (and validation) of
 * flags contained therein is left to the invocation of [PlatformMappingValue.map].
 */
class PlatformMappingFunction(optionsClasses: com.google.common.collect.ImmutableSet<java.lang.Class<out FragmentOptions?>?>?) :
    SkyFunction {
    private val optionsClasses: com.google.common.collect.ImmutableSet<java.lang.Class<out FragmentOptions?>?>

    init {
        this.optionsClasses =
            com.google.common.base.Preconditions.checkNotNull<com.google.common.collect.ImmutableSet<java.lang.Class<out FragmentOptions?>?>>(
                optionsClasses
            )
    }

    @Throws(PlatformMappingFunctionException::class, java.lang.InterruptedException::class)
    override fun compute(skyKey: SkyKey, env: SkyFunction.Environment): PlatformMappingValue? {
        val platformMappingKey: PlatformMappingKey = skyKey.argument() as PlatformMappingKey
        val workspaceRelativeMappingPath: PathFragment =
            platformMappingKey.getWorkspaceRelativeMappingPath()

        val mainRepositoryMappingValue: RepositoryMappingValue? =
            env.getValue(RepositoryMappingValue.key(RepositoryName.MAIN)) as RepositoryMappingValue?
        if (mainRepositoryMappingValue == null) {
            return null
        }
        val mainRepoContext: RepoContext =
            RepoContext.of(RepositoryName.MAIN, mainRepositoryMappingValue.repositoryMapping)

        val pkgLocator: PathPackageLocator? = PrecomputedValue.PATH_PACKAGE_LOCATOR.get(env)
        if (pkgLocator == null) {
            return null
        }

        val pathEntries: com.google.common.collect.ImmutableList<Root?> = pkgLocator.getPathEntries()
        for (root in pathEntries) {
            val rootedMappingPath: RootedPath? = RootedPath.toRootedPath(root, workspaceRelativeMappingPath)
            val fileValue: FileValue? = env.getValue(FileValue.key(rootedMappingPath)) as FileValue?
            if (fileValue == null) {
                return null
            }

            if (!fileValue.exists()) {
                continue
            }
            if (fileValue.isDirectory()) {
                throw PlatformMappingFunctionException(
                    MissingInputFileException(
                        createFailureDetail(
                            java.lang.String.format(
                                "--platform_mappings was set to '%s' relative to the top-level workspace"
                                        + " '%s' but that path refers to a directory, not a file",
                                workspaceRelativeMappingPath, root
                            ),
                            Code.PLATFORM_MAPPINGS_FILE_IS_DIRECTORY
                        ),
                        net.starlark.java.syntax.Location.BUILTIN
                    )
                )
            }

            val lines: MutableList<String?>?
            try {
                lines =
                    com.google.devtools.build.lib.vfs.FileSystemUtils.readLines(
                        fileValue.realRootedPath(
                            rootedMappingPath
                        ).asPath(), java.nio.charset.StandardCharsets.UTF_8
                    )
            } catch (e: IOException) {
                throw PlatformMappingFunctionException(e)
            }

            val parsed: Mappings?
            try {
                parsed = Companion.parse(env, lines!!, mainRepoContext)
                if (parsed == null) {
                    return null
                }
            } catch (e: PlatformMappingParsingException) {
                throw PlatformMappingFunctionException(e)
            }
            return parsed.toPlatformMappingValue(optionsClasses)
        }

        if (!platformMappingKey.wasExplicitlySetByUser()) {
            // If no flag was passed and the default mapping file does not exist treat this as if the
            // mapping file was empty rather than an error.
            return PlatformMappingValue(
                com.google.common.collect.ImmutableMap.of<Label?, ParsedFlagsValue?>(),
                com.google.common.collect.ImmutableMap.of<ParsedFlagsValue?, Label?>(),
                com.google.common.collect.ImmutableSet.of<java.lang.Class<out FragmentOptions?>?>()
            )
        }
        throw PlatformMappingFunctionException(
            MissingInputFileException(
                createFailureDetail(
                    java.lang.String.format(
                        "--platform_mappings was set to '%s' but no such file exists relative to the "
                                + "package path roots, '%s'",
                        workspaceRelativeMappingPath, pathEntries
                    ),
                    Code.PLATFORM_MAPPINGS_FILE_NOT_FOUND
                ),
                net.starlark.java.syntax.Location.BUILTIN
            )
        )
    }

    /**
     * Simple data holder to make testing easier. Only for use internal to this file/tests thereof.
     */
    @com.google.common.annotations.VisibleForTesting
    internal class Mappings(
        platformsToFlags: com.google.common.collect.ImmutableMap<Label?, ParsedFlagsValue?>?,
        flagsToPlatforms: com.google.common.collect.ImmutableMap<ParsedFlagsValue?, Label?>?
    ) {
        @kotlin.jvm.JvmField
        val platformsToFlags: com.google.common.collect.ImmutableMap<Label?, ParsedFlagsValue?>?
        @kotlin.jvm.JvmField
        val flagsToPlatforms: com.google.common.collect.ImmutableMap<ParsedFlagsValue?, Label?>?

        init {
            this.platformsToFlags = platformsToFlags
            this.flagsToPlatforms = flagsToPlatforms
        }

        fun toPlatformMappingValue(
            optionsClasses: com.google.common.collect.ImmutableSet<java.lang.Class<out FragmentOptions?>?>?
        ): PlatformMappingValue {
            return PlatformMappingValue(platformsToFlags, flagsToPlatforms, optionsClasses)
        }
    }

    @com.google.common.annotations.VisibleForTesting
    internal class PlatformMappingFunctionException : SkyFunctionException {
        constructor(cause: MissingInputFileException?) : super(PlatformMappingException(cause), Transience.PERSISTENT)

        constructor(cause: IOException?) : super(PlatformMappingException(cause), Transience.TRANSIENT)

        constructor(cause: PlatformMappingParsingException?) : super(
            PlatformMappingException(cause),
            Transience.PERSISTENT
        )
    }

    companion object {
        private fun createFailureDetail(message: String?, detailedCode: Code?): FailureDetail {
            return FailureDetail.newBuilder()
                .setMessage(message)
                .setBuildConfiguration(BuildConfiguration.newBuilder().setCode(detailedCode))
                .build()
        }

        /** Parses the given lines, returns null if not all Skyframe deps are ready.  */
        @com.google.common.annotations.VisibleForTesting
        @Throws(PlatformMappingParsingException::class, java.lang.InterruptedException::class)
        fun parse(env: SkyFunction.Environment, lines: MutableList<String?>, mainRepoContext: RepoContext): Mappings? {
            val it: com.google.common.collect.PeekingIterator<String?> =
                com.google.common.collect.Iterators.peekingIterator<String?>(
                    lines.stream()
                        .map<String?>(java.util.function.Function { obj: String? -> obj.trim() })
                        .filter(java.util.function.Predicate { line: String? -> !line.isEmpty() && !line.startsWith("#") })
                        .iterator()
                )

            if (!it.hasNext()) {
                return com.google.devtools.build.lib.skyframe.config.PlatformMappingFunction.Mappings(
                    com.google.common.collect.ImmutableMap.of<Label?, ParsedFlagsValue?>(),
                    com.google.common.collect.ImmutableMap.of<ParsedFlagsValue?, Label?>()
                )
            }

            if (!it.peek().equalsIgnoreCase("platforms:") && !it.peek().equalsIgnoreCase("flags:")) {
                throw parsingException("Expected 'platforms:' or 'flags:' but got " + it.peek())
            }

            var platformsToFlags: com.google.common.collect.ImmutableMap<Label?, ParsedFlagsValue?>? =
                com.google.common.collect.ImmutableMap.of<Label?, ParsedFlagsValue?>()
            var flagsToPlatforms: com.google.common.collect.ImmutableMap<ParsedFlagsValue?, Label?>? =
                com.google.common.collect.ImmutableMap.of<ParsedFlagsValue?, Label?>()

            if (it.peek().equalsIgnoreCase("platforms:")) {
                it.next()
                platformsToFlags = readPlatformsToFlags(it, env, mainRepoContext)
                if (platformsToFlags == null) {
                    return null
                }
            }

            if (it.hasNext()) {
                val line: String? = it.next()
                if (!line.equalsIgnoreCase("flags:")) {
                    throw parsingException("Expected 'flags:' but got " + line)
                }
                flagsToPlatforms = readFlagsToPlatforms(it, env, mainRepoContext)
                if (flagsToPlatforms == null) {
                    return null
                }
            }

            if (it.hasNext()) {
                throw parsingException("Expected end of file but got " + it.next())
            }
            return com.google.devtools.build.lib.skyframe.config.PlatformMappingFunction.Mappings(
                platformsToFlags,
                flagsToPlatforms
            )
        }

        /**
         * Converts a set of native and Starlark flag settings to a [ParsedFlagsValue], or returns
         * null if not all Skyframe deps are ready.
         */
        @Throws(PlatformMappingParsingException::class, java.lang.InterruptedException::class)
        private fun parseFlags(
            rawFlags: com.google.common.collect.ImmutableList<String?>?,
            env: SkyFunction.Environment,
            mainRepoContext: RepoContext
        ): ParsedFlagsValue? {
            val rootPackage: PackageContext? = mainRepoContext.rootPackage()
            // Passing an empty flagAliasMappings means platform mappings don't support flag aliases: if
            // a mapping sets --foo=bar but --foo is an alias for --//actual:flag, the mapping must be
            // updated to set --actual:flag=bar. Since this is a deprecated API we won't support mappings
            // here unless a compelling case demands it.
            val parsedFlagsKey: com.google.devtools.build.lib.skyframe.config.ParsedFlagsValue.Key? =
                com.google.devtools.build.lib.skyframe.config.ParsedFlagsValue.Key.Companion.create(
                    rawFlags,
                    rootPackage,  /* flagAliasMappings= */
                    com.google.common.collect.ImmutableMap.of<String?, Label?>()
                )
            try {
                return env.getValueOrThrow<com.google.devtools.common.options.OptionsParsingException?>(
                    parsedFlagsKey,
                    com.google.devtools.common.options.OptionsParsingException::class.java
                ) as ParsedFlagsValue?
            } catch (e: com.google.devtools.common.options.OptionsParsingException) {
                throw PlatformMappingParsingException(e)
            }
        }

        /**
         * Returns a parsed `platform -> flags setting`, or null if not all Skyframe deps are ready
         */
        @Throws(PlatformMappingParsingException::class, java.lang.InterruptedException::class)
        private fun readPlatformsToFlags(
            it: com.google.common.collect.PeekingIterator<String?>,
            env: SkyFunction.Environment,
            mainRepoContext: RepoContext
        ): com.google.common.collect.ImmutableMap<Label?, ParsedFlagsValue?>? {
            val platformsToFlags: com.google.common.collect.ImmutableMap.Builder<Label?, ParsedFlagsValue?> =
                com.google.common.collect.ImmutableMap.builder<Label?, ParsedFlagsValue?>()
            var needSkyframeDeps = false
            while (it.hasNext() && !it.peek().equalsIgnoreCase("flags:")) {
                val platform: Label = readPlatform(it, mainRepoContext)
                val flags: com.google.common.collect.ImmutableList<String?> = readFlags(it)
                val parsedFlags: ParsedFlagsValue? = parseFlags(flags, env, mainRepoContext)
                if (parsedFlags == null) {
                    needSkyframeDeps = true
                } else {
                    platformsToFlags.put(platform, parsedFlags)
                }
            }

            if (needSkyframeDeps) {
                return null
            }

            try {
                return platformsToFlags.buildOrThrow()
            } catch (e: java.lang.IllegalArgumentException) {
                throw parsingException(
                    "Got duplicate platform entries but each platform key must be unique", e
                )
            }
        }

        /**
         * Returns a parsed `flags -> platform setting`, or null if not all Skyframe deps are ready
         */
        @Throws(PlatformMappingParsingException::class, java.lang.InterruptedException::class)
        private fun readFlagsToPlatforms(
            it: com.google.common.collect.PeekingIterator<String?>,
            env: SkyFunction.Environment,
            mainRepoContext: RepoContext
        ): com.google.common.collect.ImmutableMap<ParsedFlagsValue?, Label?>? {
            val flagsToPlatforms: com.google.common.collect.ImmutableMap.Builder<ParsedFlagsValue?, Label?> =
                com.google.common.collect.ImmutableMap.builder<ParsedFlagsValue?, Label?>()
            var needSkyframeDeps = false
            while (it.hasNext() && it.peek().startsWith("--")) {
                val flags: com.google.common.collect.ImmutableList<String?> = readFlags(it)
                val platform: Label = readPlatform(it, mainRepoContext)

                val parsedFlags: ParsedFlagsValue? = parseFlags(flags, env, mainRepoContext)
                if (parsedFlags == null) {
                    needSkyframeDeps = true
                } else {
                    flagsToPlatforms.put(parsedFlags, platform)
                }
            }

            if (needSkyframeDeps) {
                return null
            }

            try {
                return flagsToPlatforms.buildOrThrow()
            } catch (e: java.lang.IllegalArgumentException) {
                throw parsingException("Got duplicate flags entries but each flags key must be unique", e)
            }
        }

        @Throws(PlatformMappingParsingException::class)
        private fun readPlatform(
            it: com.google.common.collect.PeekingIterator<String?>,
            mainRepoContext: RepoContext?
        ): Label {
            if (!it.hasNext()) {
                throw parsingException("Expected platform label but got end of file")
            }

            val line: String? = it.next()
            try {
                return Label.parseWithRepoContext(line, mainRepoContext)
            } catch (e: LabelSyntaxException) {
                throw parsingException("Expected platform label but got " + line, e)
            }
        }

        @Throws(PlatformMappingParsingException::class)
        private fun readFlags(it: com.google.common.collect.PeekingIterator<String?>): com.google.common.collect.ImmutableList<String?> {
            val flags: com.google.common.collect.ImmutableList.Builder<String?> =
                com.google.common.collect.ImmutableList.builder<String?>()
            // Note: Short form flags are not supported.
            while (it.hasNext() && it.peek().startsWith("--")) {
                flags.add(it.next())
            }
            val parsedFlags: com.google.common.collect.ImmutableList<String?> = flags.build()
            if (parsedFlags.isEmpty()) {
                throw parsingException(
                    if (it.hasNext())
                        "Expected a standard format flag (starting with --) but got " + it.peek()
                    else
                        "Expected a flag but got end of file"
                )
            }
            return parsedFlags
        }

        private fun parsingException(
            message: String?,
            cause: java.lang.Exception? = null
        ): PlatformMappingParsingException {
            return PlatformMappingParsingException(message, cause)
        }
    }
}
