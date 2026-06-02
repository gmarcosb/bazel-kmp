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
package com.google.devtools.build.lib.skyframe

import com.google.devtools.build.lib.actions.FileValue

/** A [SkyFunction] for [DirectoryTreeDigestValue]s.  */
class DirectoryTreeDigestFunction : SkyFunction {
    @Throws(java.lang.InterruptedException::class, DirectoryTreeDigestFunctionException::class)
    override fun compute(skyKey: SkyKey?, env: SkyFunction.Environment): SkyValue? {
        val patternCache: MutableMap<String?, java.util.regex.Pattern?> = HashMap<String?, java.util.regex.Pattern?>()
        val key: com.google.devtools.build.lib.skyframe.DirectoryTreeDigestValue.Key =
            skyKey as com.google.devtools.build.lib.skyframe.DirectoryTreeDigestValue.Key
        val rootedPath: RootedPath = key.rootedPath
        if (Companion.excludes(rootedPath, key.globBase, key.excludes, patternCache)) {
            // The path we are trying to compute a digest for is excluded.
            // This should only happen at the very beginning/root of a tree digest as the subsequent
            // computation of digests for child nodes should be excluded before they are asked to be
            // computed. Eg. user asks to watch /some/path and excludes everything under it ('**').  This
            // would be a nonsensical action, so throw an error.
            throw DirectoryTreeDigestFunctionException(
                FileNotFoundException(
                    String.format(
                        "Tried to compute the digest of path '%s' but this path was filtered out by glob"
                                + " exclude base '%s' and excludes: %s",
                        rootedPath, key.globBase, key.excludes
                    )
                )
            )
        }
        val dirListingValue: DirectoryListingValue? =
            env.getValue(DirectoryListingValue.Companion.key(rootedPath)) as DirectoryListingValue?
        if (dirListingValue == null) {
            return null
        }

        // Get the names of entries directly in this directory, and sort them. This sets the basis for
        // subsequent digests.
        val sortedDirents: com.google.common.collect.ImmutableSet<String?> =
            StreamSupport.stream<com.google.devtools.build.lib.vfs.Dirent?>(
                dirListingValue.getDirents().spliterator(),  /* parallel= */false
            )
                .map<String?> { obj: com.google.devtools.build.lib.vfs.Dirent? -> obj.getName() }
                .filter { entry: String? ->
                    val path = rootedPath.getRootRelativePath().getRelative(entry).toString()
                    !Companion.excludes(path, key.globBase, key.excludes, patternCache)
                }
                .sorted()
                .collect(com.google.common.collect.ImmutableSet.toImmutableSet<String?>())

        // Turn each entry into a FileValue.
        val fileValues: com.google.common.collect.ImmutableList<com.google.devtools.build.lib.util.Pair<RootedPath?, FileValue?>>? =
            getFileValues(env, sortedDirents, rootedPath)
        if (fileValues == null) {
            return null
        }

        // For each entry that is a directory (or a symlink to a directory), find its own
        // DirectoryTreeDigestValue.
        val subDirTreeDigests: com.google.common.collect.ImmutableList<String?>? =
            getSubDirTreeDigests(env, fileValues, key)
        if (subDirTreeDigests == null) {
            return null
        }

        // Finally, we're ready to digest everything together!
        val fp: Fingerprint = Fingerprint()
        fp.addStrings(sortedDirents)
        fp.addStrings(subDirTreeDigests)
        try {
            for (rootedPathAndFileValue in fileValues) {
                val direntRootedPath: RootedPath? = rootedPathAndFileValue.getFirst()
                val fileValue: FileValue? = rootedPathAndFileValue.getSecond()
                fp.addInt(fileValue.realFileStateValue().getType().ordinal())
                if (fileValue.isFile()) {
                    var digest: ByteArray? = fileValue.realFileStateValue().getDigest()
                    if (digest == null) {
                        // Fast digest not available, or it would have been in the FileValue.
                        digest = fileValue.realRootedPath(direntRootedPath).asPath().getDigest()
                    }
                    fp.addBytes(digest)
                }
            }
        } catch (e: IOException) {
            throw DirectoryTreeDigestFunctionException(e)
        }

        return DirectoryTreeDigestValue.Companion.of(fp.hexDigestAndReset())
    }

    private class DirectoryTreeDigestFunctionException(e: IOException?) : SkyFunctionException(e, Transience.TRANSIENT)
    companion object {
        @Throws(java.lang.InterruptedException::class)
        private fun getFileValues(
            env: SkyFunction.Environment,
            sortedDirents: com.google.common.collect.ImmutableSet<String?>,
            rootedPath: RootedPath
        ): com.google.common.collect.ImmutableList<com.google.devtools.build.lib.util.Pair<RootedPath?, FileValue?>>? {
            val fileValueKeys: com.google.common.collect.ImmutableSet<com.google.devtools.build.lib.skyframe.FileKey?> =
                sortedDirents.stream()
                    .map<Any?> { dirent: String? ->
                        FileValue.key(
                            RootedPath.toRootedPath(
                                rootedPath.getRoot(),
                                rootedPath.getRootRelativePath().getRelative(dirent)
                            )
                        )
                    }
                    .collect(TODO("Cannot convert element"))<Object> com . google . common . collect . ImmutableSet . toImmutableSet < kotlin . Any ? > ()

            val result: SkyframeLookupResult = env.getValuesAndExceptions(fileValueKeys)
            if (env.valuesMissing()) {
                return null
            }
            val fileValues: com.google.common.collect.ImmutableList<com.google.devtools.build.lib.util.Pair<RootedPath?, FileValue?>> =
                fileValueKeys.stream()
                    .map<com.google.devtools.build.lib.util.Pair<RootedPath?, Any?>?> { k: com.google.devtools.build.lib.skyframe.FileKey? ->
                        com.google.devtools.build.lib.util.Pair.of<RootedPath?, Any?>(
                            k.argument() as RootedPath?,
                            result.get(k) as FileValue?
                        )
                    }
                    .collect(TODO("Cannot convert element"))<Object> com . google . common . collect . ImmutableList . toImmutableList < kotlin . Any ? > ()

            if (env.valuesMissing()) {
                return null
            }
            return fileValues
        }

        @Throws(java.lang.InterruptedException::class)
        private fun getSubDirTreeDigests(
            env: SkyFunction.Environment,
            fileValues: com.google.common.collect.ImmutableList<com.google.devtools.build.lib.util.Pair<RootedPath?, FileValue?>>,
            key: com.google.devtools.build.lib.skyframe.DirectoryTreeDigestValue.Key
        ): com.google.common.collect.ImmutableList<String?>? {
            val dirTreeDigestValueKeys: com.google.common.collect.ImmutableSet<SkyKey?> =
                fileValues.stream()
                    .filter { p: com.google.devtools.build.lib.util.Pair<RootedPath?, FileValue?>? ->
                        p.getSecond().isDirectory()
                    }
                    .map<com.google.devtools.build.lib.skyframe.DirectoryTreeDigestValue.Key?> { p: com.google.devtools.build.lib.util.Pair<RootedPath?, FileValue?>? ->
                        DirectoryTreeDigestValue.Companion.key( /* rootedPath= */
                            p.getSecond().realRootedPath(p.getFirst()),  /* globBase= */
                            key.globBase,  /* excludes= */
                            key.excludes
                        )
                    }
                    .collect(com.google.common.collect.ImmutableSet.toImmutableSet<SkyKey?>())
            val result: SkyframeLookupResult = env.getValuesAndExceptions(dirTreeDigestValueKeys)
            if (env.valuesMissing()
                || dirTreeDigestValueKeys.stream().map<SkyValue?> { skyKey: SkyKey? -> result.get(skyKey) }
                    .anyMatch { obj: SkyValue? -> java.util.Objects.isNull(obj) }
            ) {
                return null
            }
            return dirTreeDigestValueKeys.stream()
                .map<SkyValue?> { skyKey: SkyKey? -> result.get(skyKey) }
                .map<DirectoryTreeDigestValue?> { obj: SkyValue? -> DirectoryTreeDigestValue::class.java.cast(obj) }
                .map<String?>(DirectoryTreeDigestValue::hexDigest)
                .collect(com.google.common.collect.ImmutableList.toImmutableList<String?>())
        }

        /** Returns if the given `rootedPath` would be filtered/excluded out.  */
        fun excludes(
            rootedPath: RootedPath,
            globBase: RootedPath,
            excludes: com.google.common.collect.ImmutableList<String?>,
            patternCache: MutableMap<String?, java.util.regex.Pattern?>?
        ): Boolean {
            // Are we comparing the same roots?
            if (rootedPath.getRoot() != globBase.getRoot()) {
                return false
            }
            val path = rootedPath.getRootRelativePath().toString()
            return Companion.excludes(path, globBase, excludes, patternCache)
        }

        /** Returns if the given `path` would be filtered/excluded out.  */
        fun excludes(
            path: String?,
            globBase: RootedPath,
            excludes: com.google.common.collect.ImmutableList<String?>,
            patternCache: MutableMap<String?, java.util.regex.Pattern?>?
        ): Boolean {
            val baseExclude: PathFragment = globBase.getRootRelativePath()
            for (exclude in excludes) {
                val excludePattern = baseExclude.getRelative(exclude).toString()
                if (UnixGlob.matches(excludePattern, path, patternCache)) {
                    return true
                }
            }
            return false
        }
    }
}
