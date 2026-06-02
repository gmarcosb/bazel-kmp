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
package com.google.devtools.build.lib.skyframe

import com.google.devtools.build.lib.rules.repository.RepositoryDirectoryValue.VENDOR_DIRECTORY

/**
 * A [SkyFunction] for [IgnoredSubdirectoriesValue].
 * 
 * 
 * It is used to compute which directories should be ignored in a package. These either come from
 * the `.bazelignore` file or from the `ignored_directories()` function in `REPO.bazel`.
 * 
 * 
 * This is intended for directories containing non-bazel sources (either generated, or versioned
 * sources built by other tools) that happen to contain a file called BUILD.
 * 
 * 
 * For the time being, this ignore functionality is limited by the fact that it is applied only
 * after pattern expansion. So if a pattern expansion fails (e.g., due to symlink-cycles) and
 * therefore fails the build, this ignore functionality currently has no chance to kick in.
 */
class IgnoredSubdirectoriesFunction private constructor() : SkyFunction {
    @Throws(IgnoredSubdirectoriesFunctionException::class, java.lang.InterruptedException::class)
    private fun computeIgnoredPatterns(
        env: SkyFunction.Environment, repositoryName: RepositoryName?
    ): com.google.common.collect.ImmutableList<String?>? {
        try {
            val repoFileValue: RepoFileValue? =
                env.getValueOrThrow<IOException?, BadRepoFileException?>(
                    RepoFileValue.Companion.key(repositoryName),
                    IOException::class.java,
                    BadRepoFileException::class.java
                ) as RepoFileValue?

            if (env.valuesMissing()) {
                return null
            }

            return repoFileValue.ignoredDirectories
        } catch (e: IOException) {
            throw IgnoredSubdirectoriesFunctionException(e)
        } catch (e: BadRepoFileException) {
            throw IgnoredSubdirectoriesFunctionException(e)
        }
    }

    @Throws(IgnoredSubdirectoriesFunctionException::class, java.lang.InterruptedException::class)
    private fun computeIgnoredPrefixes(
        env: SkyFunction.Environment, repositoryName: RepositoryName
    ): com.google.common.collect.ImmutableSet<PathFragment?>? {
        val ignoredPrefixesBuilder: com.google.common.collect.ImmutableSet.Builder<PathFragment?> =
            com.google.common.collect.ImmutableSet.builder<PathFragment?>()
        val pkgLocator: PathPackageLocator? = PrecomputedValue.Companion.PATH_PACKAGE_LOCATOR.get(env)
        if (env.valuesMissing()) {
            return null
        }

        if (repositoryName.isMain()) {
            var vendorDir: PathFragment? = null
            if (VENDOR_DIRECTORY.get(env).isPresent()) {
                vendorDir = VENDOR_DIRECTORY.get(env).get().asFragment()
            }

            for (packagePathEntry in pkgLocator.getPathEntries()) {
                val workspaceRoot: PathFragment? = packagePathEntry.asPath().asFragment()
                if (vendorDir != null && vendorDir.startsWith(workspaceRoot)) {
                    ignoredPrefixesBuilder.add(vendorDir.relativeTo(workspaceRoot))
                }

                val rootedPrefixFile: RootedPath =
                    RootedPath.toRootedPath(packagePathEntry, BAZELIGNORE_REPOSITORY_RELATIVE_PATH)
                val prefixFileValue: FileValue? = env.getValue(FileValue.key(rootedPrefixFile)) as FileValue?
                if (prefixFileValue == null) {
                    return null
                }
                if (prefixFileValue.isFile()) {
                    getIgnoredPrefixes(rootedPrefixFile, ignoredPrefixesBuilder)
                    break
                }
            }
        } else {
            // Make sure the repository is fetched.
            val repositoryValue: RepositoryDirectoryValue? =
                env.getValue(RepositoryDirectoryValue.key(repositoryName)) as RepositoryDirectoryValue?
            if (repositoryValue == null) {
                return null
            }
            if (repositoryValue is Success) {
                val rootedPrefixFile: RootedPath =
                    RootedPath.toRootedPath(repositoryValue.root(), BAZELIGNORE_REPOSITORY_RELATIVE_PATH)
                val prefixFileValue: FileValue? = env.getValue(FileValue.key(rootedPrefixFile)) as FileValue?
                if (prefixFileValue == null) {
                    return null
                }
                if (prefixFileValue.isFile()) {
                    getIgnoredPrefixes(rootedPrefixFile, ignoredPrefixesBuilder)
                }
            }
        }

        return ignoredPrefixesBuilder.build()
    }

    @Throws(IgnoredSubdirectoriesFunctionException::class, java.lang.InterruptedException::class)
    override fun compute(key: SkyKey, env: SkyFunction.Environment): SkyValue? {
        val repositoryName: RepositoryName = key.argument() as RepositoryName

        val ignoredPatterns: com.google.common.collect.ImmutableList<String?>? =
            computeIgnoredPatterns(env, repositoryName)
        if (env.valuesMissing()) {
            return null
        }

        val ignoredPrefixes: com.google.common.collect.ImmutableSet<PathFragment?>? =
            computeIgnoredPrefixes(env, repositoryName)
        if (env.valuesMissing()) {
            return null
        }

        return IgnoredSubdirectoriesValue.Companion.of(ignoredPrefixes, ignoredPatterns)
    }

    private class PathFragmentLineProcessor

        : com.google.common.io.LineProcessor<com.google.common.collect.ImmutableSet<PathFragment?>?> {
        private val fragments: com.google.common.collect.ImmutableSet.Builder<PathFragment?> =
            com.google.common.collect.ImmutableSet.builder<PathFragment?>()

        override fun processLine(line: String): Boolean {
            if (!line.isEmpty() && !line.startsWith("#")) {
                fragments.add(PathFragment.create(line))

                // This is called for its side-effects rather than its output.
                // Specifically, it validates that the line is a valid path. This
                // doesn't do much on UNIX machines where only NUL is an invalid
                // character but can reject paths on Windows.
                //
                // This logic would need to be adjusted if wildcards are ever supported
                // (https://github.com/bazelbuild/bazel/issues/7093).
                val unused: java.nio.file.Path? = java.nio.file.Path.of(line)
            }
            return true
        }

        val result: com.google.common.collect.ImmutableSet<PathFragment?>
            get() = fragments.build()
    }

    private class IgnoredSubdirectoriesFunctionException : SkyFunctionException {
        constructor(e: InconsistentFilesystemException?) : super(e, Transience.TRANSIENT)

        constructor(e: InvalidIgnorePathException?) : super(e, Transience.PERSISTENT)

        constructor(e: IOException?) : super(e, Transience.TRANSIENT)

        constructor(e: BadRepoFileException?) : super(e, Transience.PERSISTENT)
    }

    companion object {
        /** Repository-relative path of the bazelignore file.  */
        @kotlin.jvm.JvmField
        val BAZELIGNORE_REPOSITORY_RELATIVE_PATH: PathFragment = PathFragment.create(".bazelignore")

        /** Singleton instance of this [SkyFunction].  */
        @kotlin.jvm.JvmField
        val INSTANCE: IgnoredSubdirectoriesFunction = IgnoredSubdirectoriesFunction()

        /**
         * A version of [IgnoredSubdirectoriesFunction] that always returns the empty value.
         * 
         * 
         * Used for tests where the extra complications incurred by evaluating the function are
         * undesired.
         */
        @kotlin.jvm.JvmField
        val NOOP: SkyFunction =
            SkyFunction { skyKey: SkyKey?, env: SkyFunction.Environment? -> IgnoredSubdirectoriesValue.Companion.EMPTY }

        @Throws(IgnoredSubdirectoriesFunctionException::class)
        fun getIgnoredPrefixes(
            patternFile: RootedPath,
            ignoredDirectoriesBuilder: com.google.common.collect.ImmutableSet.Builder<PathFragment?>
        ) {
            try {
                java.io.InputStreamReader(
                    patternFile.asPath().getInputStream(),
                    java.nio.charset.StandardCharsets.UTF_8
                ).use { reader ->
                    for (ignored in com.google.common.io.CharStreams.readLines<com.google.common.collect.ImmutableSet<PathFragment>?>(
                        reader,
                        PathFragmentLineProcessor()
                    )) {
                        if (ignored.isAbsolute()) {
                            throw IgnoredSubdirectoriesFunctionException(
                                InvalidIgnorePathException(
                                    patternFile.asPath().toString(),
                                    String.format("'%s': cannot be an absolute path", ignored)
                                )
                            )
                        }

                        ignoredDirectoriesBuilder.add(ignored)
                    }
                }
            } catch (e: IOException) {
                val errorMessage = if (e.message != null) "error '" + e.message + "'" else "an error"
                throw IgnoredSubdirectoriesFunctionException(
                    InconsistentFilesystemException(
                        (patternFile.asPath()
                            .toString() + " is not readable because: "
                                + errorMessage
                                + ". Was it modified mid-build?")
                    )
                )
            } catch (e: InvalidPathException) {
                throw IgnoredSubdirectoriesFunctionException(
                    InvalidIgnorePathException(patternFile.asPath().toString(), e.message)
                )
            }
        }
    }
}
