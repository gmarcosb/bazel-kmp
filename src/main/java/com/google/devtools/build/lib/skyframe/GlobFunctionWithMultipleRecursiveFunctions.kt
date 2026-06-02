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
package com.google.devtools.build.lib.skyframe

import com.google.devtools.build.lib.actions.FileValue

/**
 * The canonical approach to compute [GlobFunction] by recursively creating sub-Glob nodes
 * when handling subdirectories under a package.
 */
class GlobFunctionWithMultipleRecursiveFunctions : GlobFunction() {
    @Throws(GlobException::class, java.lang.InterruptedException::class)
    override fun compute(skyKey: SkyKey, env: SkyFunction.Environment): SkyValue? {
        val glob: GlobDescriptor = skyKey.argument() as GlobDescriptor
        val globberOperation: Globber.Operation? = glob.globberOperation()

        val repositoryName: RepositoryName? = glob.getPackageId().getRepository()
        val ignoredSubdirectories: IgnoredSubdirectoriesValue? =
            env.getValue(IgnoredSubdirectoriesValue.Companion.key(repositoryName)) as IgnoredSubdirectoriesValue?
        if (env.valuesMissing()) {
            return null
        }

        val globSubdir: PathFragment = glob.getSubdir()
        val dirPathFragment: PathFragment = glob.getPackageId().getPackageFragment().getRelative(globSubdir)

        if (ignoredSubdirectories.asIgnoredSubdirectories().matchingEntry(dirPathFragment) != null) {
            return GlobValueWithNestedSet.Companion.EMPTY
        }

        val pattern: String = glob.getPattern()

        // Note that the glob's package is assumed to exist which implies that the package's BUILD file
        // exists which implies that the package's directory exists.
        if (globSubdir != PathFragment.EMPTY_FRAGMENT) {
            val subDirFragment: PathFragment =
                glob.getPackageId().getPackageFragment().getRelative(globSubdir)

            val globSubdirPkgLookupValue: PackageLookupValue? =
                env.getValue(
                    key(PackageIdentifier.create(repositoryName, subDirFragment))
                ) as PackageLookupValue?
            if (globSubdirPkgLookupValue == null) {
                return null
            }

            if (globSubdirPkgLookupValue.packageExists()) {
                // We crossed the package boundary, that is, pkg/subdir contains a BUILD file and thus
                // defines another package, so glob expansion should not descend into
                // that subdir.
                //
                // For SUBPACKAGES, we encounter this when all remaining patterns in the glob expression
                // are `**`s. In that case we should include the subpackage's PathFragment (relative to the
                // package fragment) in the GlobValue.getMatches. Otherwise, return EMPTY.
                if (globberOperation === Globber.Operation.SUBPACKAGES
                    && java.util.Arrays.stream<String?>(pattern.split("/".toRegex()).dropLastWhile { it.isEmpty() }
                        .toTypedArray()).allMatch { anObject: String? -> "**".equals(anObject) }
                ) {
                    return GlobValueWithNestedSet(
                        NestedSetBuilder.< PathFragment > stableOrder < PathFragment ? > ()
                            .add(subDirFragment.relativeTo(glob.getPackageId().getPackageFragment()))
                            .build()
                    )
                }
                return GlobValueWithNestedSet.Companion.EMPTY
            } else if (globSubdirPkgLookupValue
                        is IncorrectRepositoryReferencePackageLookupValue
            ) {
                // We crossed a repository boundary, so glob expansion should not descend into that subdir.
                return GlobValueWithNestedSet.Companion.EMPTY
            }
        }

        // Split off the first path component of the pattern.
        val slashPos: Int = pattern.indexOf('/')
        val patternHead: String?
        val patternTail: String?
        if (slashPos == -1) {
            patternHead = pattern
            patternTail = null
        } else {
            // Substrings will share the backing array of the original glob string. That should be fine.
            patternHead = pattern.substring(0, slashPos)
            patternTail = pattern.substring(slashPos + 1)
        }

        val globMatchesBareFile = patternTail == null

        val matches: NestedSetBuilder<PathFragment?> = NestedSetBuilder.stableOrder()

        val dirRootedPath: RootedPath? = RootedPath.toRootedPath(glob.getPackageRoot(), dirPathFragment)
        // Note that we have good reason to believe the directory exists: if this is the
        // top-level directory of the package, the package's existence implies the directory's
        // existence; if this is a lower-level directory in the package, then we got here from
        // previous directory listings. Filesystem operations concurrent with build could mean the
        // directory no longer exists, but DirectoryListingFunction handles that gracefully.
        val directoryListingKey: SkyKey? = DirectoryListingValue.Companion.key(dirRootedPath)
        var listingValue: DirectoryListingValue? = null

        val patternHeadContainsGlobs = Companion.containsGlobs(patternHead!!)
        val patternHeadIsStarStar = patternHead == "**"
        if (patternHeadIsStarStar) {
            // "**" also matches an empty segment, so try the case where it is not present.
            if (globMatchesBareFile) {
                // Recursive globs aren't supposed to match the package's directory.
                if (globberOperation === Globber.Operation.FILES_AND_DIRS
                    && globSubdir != PathFragment.EMPTY_FRAGMENT
                ) {
                    matches.add(globSubdir)
                }
            } else {
                // Optimize away a Skyframe restart by requesting the DirectoryListingValue dep and
                // recursive GlobValue dep in a single batch.

                val keyForRecursiveGlobInCurrentDirectory: SkyKey? =
                    GlobValue.Companion.internalKey(
                        glob.getPackageId(),
                        glob.getPackageRoot(),
                        globSubdir,
                        patternTail,
                        globberOperation
                    )
                val listingAndRecursiveGlobResult: SkyframeLookupResult =
                    env.getValuesAndExceptions(
                        com.google.common.collect.ImmutableList.of<SkyKey?>(
                            keyForRecursiveGlobInCurrentDirectory,
                            directoryListingKey
                        )
                    )
                if (env.valuesMissing()) {
                    return null
                }
                val globValue: GlobValue? =
                    listingAndRecursiveGlobResult.get(keyForRecursiveGlobInCurrentDirectory) as GlobValue?
                if (globValue == null) {
                    // has exception, will be handled later.
                    return null
                }
                com.google.common.base.Preconditions.checkState(globValue is GlobValueWithNestedSet)
                matches.addTransitive((globValue as GlobValueWithNestedSet).getMatchesInNestedSet())
                listingValue =
                    listingAndRecursiveGlobResult.get(directoryListingKey) as DirectoryListingValue?
            }
        }

        if (listingValue == null) {
            listingValue = env.getValue(directoryListingKey) as DirectoryListingValue?
            if (listingValue == null) {
                return null
            }
        }

        // Now that we have the directory listing, we do three passes over it so as to maximize
        // skyframe batching:
        // (1) Process every dirent, keeping track of values we need to request if the dirent cannot
        //     be processed with current information (symlink targets and subdirectory globs/package
        //     lookups for some subdirectories).
        // (2) Get those values and process the symlinks, keeping track of subdirectory globs/package
        //     lookups we may need to request in case the symlink's target is a directory.
        // (3) Process the necessary subdirectories.
        val direntsSize: Int = listingValue.getDirents().size
        val symlinkFileMap: MutableMap<SkyKey?, com.google.devtools.build.lib.vfs.Dirent> =
            com.google.common.collect.Maps.newHashMapWithExpectedSize<SkyKey?, com.google.devtools.build.lib.vfs.Dirent?>(
                direntsSize
            )
        val subdirMap: MutableMap<SkyKey?, com.google.devtools.build.lib.vfs.Dirent?> =
            com.google.common.collect.Maps.newHashMapWithExpectedSize<SkyKey?, com.google.devtools.build.lib.vfs.Dirent?>(
                direntsSize
            )
        val sortedResultMap: MutableMap<com.google.devtools.build.lib.vfs.Dirent?, Any?> =
            com.google.common.collect.Maps.newTreeMap<com.google.devtools.build.lib.vfs.Dirent?, Any?>()
        val subdirPattern = if (patternHeadIsStarStar) glob.getPattern() else patternTail
        // First pass: do normal files and collect SkyKeys to request for subdirectories and symlinks.
        for (dirent in listingValue.getDirents()) {
            val direntType: com.google.devtools.build.lib.vfs.Dirent.Type = dirent.getType()
            val fileName: String = dirent.getName()
            val patternHeadMatchesDirent =
                if (patternHeadContainsGlobs)
                    UnixGlob.matches(patternHead, fileName, regexPatternCache)
                else
                    (patternHead == fileName)
            if (!patternHeadMatchesDirent) {
                continue
            }

            if (direntType == com.google.devtools.build.lib.vfs.Dirent.Type.SYMLINK) {
                // TODO(bazel-team): Consider extracting the symlink resolution logic.
                // For symlinks, look up the corresponding FileValue. This ensures that if the symlink
                // changes and "switches types" (say, from a file to a directory), this value will be
                // invalidated. We also need the target's type to properly process the symlink.
                symlinkFileMap.put(
                    FileValue.key(
                        RootedPath.toRootedPath(
                            glob.getPackageRoot(), dirPathFragment.getRelative(fileName)
                        )
                    ),
                    dirent
                )
                continue
            }

            if (direntType == com.google.devtools.build.lib.vfs.Dirent.Type.DIRECTORY) {
                val keyToRequest: SkyKey? = getSkyKeyForSubdir(fileName, glob, subdirPattern)
                if (keyToRequest != null) {
                    subdirMap.put(keyToRequest, dirent)
                }
            } else if (globMatchesBareFile && globberOperation !== Globber.Operation.SUBPACKAGES) {
                sortedResultMap.put(dirent, glob.getSubdir().getRelative(fileName))
            }
        }

        val subdirAndSymlinksKeys: MutableSet<SkyKey> =
            com.google.common.collect.Sets.union<SkyKey?>(subdirMap.keys, symlinkFileMap.keys)
        val subdirAndSymlinksResult: SkyframeLookupResult =
            env.getValuesAndExceptions(subdirAndSymlinksKeys)
        if (env.valuesMissing()) {
            return null
        }
        val symlinkSubdirMap: MutableMap<SkyKey?, com.google.devtools.build.lib.vfs.Dirent?> =
            com.google.common.collect.Maps.newHashMapWithExpectedSize<SkyKey?, com.google.devtools.build.lib.vfs.Dirent?>(
                symlinkFileMap.size
            )
        // Second pass: process the symlinks and subdirectories from the first pass, and maybe
        // collect further SkyKeys if fully resolved symlink targets are themselves directories.
        // Also process any known directories.
        for (subdirAndSymlinksKey in subdirAndSymlinksKeys) {
            if (symlinkFileMap.containsKey(subdirAndSymlinksKey)) {
                val symlinkFileValue: FileValue? = subdirAndSymlinksResult.get(subdirAndSymlinksKey) as FileValue?
                if (symlinkFileValue == null) {
                    return null
                }
                if (!symlinkFileValue.isSymlink()) {
                    throw GlobException(
                        InconsistentFilesystemException(
                            ("readdir and stat disagree about whether "
                                    + (subdirAndSymlinksKey.argument() as RootedPath).asPath()
                                    + " is a symlink.")
                        ),
                        Transience.TRANSIENT
                    )
                }
                if (!symlinkFileValue.exists()) {
                    continue
                }

                // This check is more strict than necessary: we raise an error if globbing traverses into
                // a directory for any reason, even though it's only necessary if that reason was the
                // resolution of a recursive glob ("**"). Fixing this would require plumbing the ancestor
                // symlink information through DirectoryListingValue.
                if (symlinkFileValue.isDirectory()
                    && symlinkFileValue.unboundedAncestorSymlinkExpansionChain() != null
                ) {
                    val uniquenessKey: SkyKey? =
                        FileSymlinkInfiniteExpansionUniquenessFunction.key(
                            symlinkFileValue.unboundedAncestorSymlinkExpansionChain()
                        )
                    env.getValue(uniquenessKey)
                    if (env.valuesMissing()) {
                        return null
                    }

                    val symlinkException: FileSymlinkInfiniteExpansionException =
                        FileSymlinkInfiniteExpansionException(
                            symlinkFileValue.pathToUnboundedAncestorSymlinkExpansionChain(),
                            symlinkFileValue.unboundedAncestorSymlinkExpansionChain()
                        )
                    throw GlobException(symlinkException, Transience.PERSISTENT)
                }

                val dirent: com.google.devtools.build.lib.vfs.Dirent = symlinkFileMap.get(subdirAndSymlinksKey)
                val fileName: String = dirent.getName()
                if (symlinkFileValue.isDirectory()) {
                    val keyToRequest: SkyKey? = getSkyKeyForSubdir(fileName, glob, subdirPattern)
                    if (keyToRequest != null) {
                        symlinkSubdirMap.put(keyToRequest, dirent)
                    }
                } else if (globMatchesBareFile && globberOperation !== Globber.Operation.SUBPACKAGES) {
                    sortedResultMap.put(dirent, glob.getSubdir().getRelative(fileName))
                }
            } else {
                val value: SkyValue? = subdirAndSymlinksResult.get(subdirAndSymlinksKey)
                if (value == null) {
                    return null
                }
                processSubdir(
                    java.util.Map.entry<SkyKey?, SkyValue?>(subdirAndSymlinksKey, value),
                    subdirMap,
                    glob,
                    sortedResultMap
                )
            }
        }

        val symlinkSubdirKeys: MutableSet<SkyKey> = symlinkSubdirMap.keys
        val symlinkSubdirResult: SkyframeLookupResult = env.getValuesAndExceptions(symlinkSubdirKeys)
        if (env.valuesMissing()) {
            return null
        }
        // Third pass: do needed subdirectories of symlinked directories discovered during the second
        // pass.
        for (symlinkSubdirKey in symlinkSubdirKeys) {
            val symlinkSubdirValue: SkyValue? = symlinkSubdirResult.get(symlinkSubdirKey)
            if (symlinkSubdirValue == null) {
                return null
            }
            processSubdir(
                java.util.Map.entry<SkyKey?, SkyValue?>(symlinkSubdirKey, symlinkSubdirValue),
                symlinkSubdirMap,
                glob,
                sortedResultMap
            )
        }
        for (fileMatches in sortedResultMap.entries) {
            addToMatches(fileMatches.value, matches)
        }

        com.google.common.base.Preconditions.checkState(!env.valuesMissing(), skyKey)

        val matchesBuilt: NestedSet<PathFragment?> = matches.build()
        // Use the same value to represent that we did not match anything.
        if (matchesBuilt.isEmpty()) {
            return GlobValueWithNestedSet.Companion.EMPTY
        }
        return GlobValueWithNestedSet(matchesBuilt)
    }

    companion object {
        private fun processSubdir(
            keyAndValue: MutableMap.MutableEntry<SkyKey?, SkyValue?>,
            subdirMap: MutableMap<SkyKey?, com.google.devtools.build.lib.vfs.Dirent?>,
            glob: GlobDescriptor,
            sortedResultMap: MutableMap<com.google.devtools.build.lib.vfs.Dirent?, Any?>
        ) {
            val dirent: com.google.devtools.build.lib.vfs.Dirent =
                com.google.common.base.Preconditions.checkNotNull<com.google.devtools.build.lib.vfs.Dirent>(
                    subdirMap.get(keyAndValue.key), keyAndValue
                )
            val fileName: String = dirent.getName()
            val dirMatches = getSubdirMatchesFromSkyValue(fileName, glob, keyAndValue.value)
            if (dirMatches != null) {
                sortedResultMap.put(dirent, dirMatches)
            }
        }

        /** Returns true if the given pattern contains globs.  */
        private fun containsGlobs(pattern: String): Boolean {
            return pattern.contains("*") || pattern.contains("?")
        }

        // cast to NestedSet<PathFragment>
        private fun addToMatches(toAdd: Any?, matches: NestedSetBuilder<PathFragment?>) {
            if (toAdd is PathFragment) {
                matches.add(toAdd)
            } else if (toAdd is NestedSet) {
                matches.addTransitive(toAdd as NestedSet<PathFragment?>?)
            }
            // else Not actually a valid type and ignore.
        }

        /**
         * Includes the given file/directory in the glob.
         * 
         * 
         * `fileName` must exist.
         * 
         * 
         * `isDirectory` must be true iff the file is a directory.
         * 
         * 
         * Returns a [SkyKey] for a value that is needed to compute the files that will be added
         * to `matches`, or `null` if no additional value is needed. The returned value should
         * be opaquely passed to [.getSubdirMatchesFromSkyValue].
         */
        private fun getSkyKeyForSubdir(
            fileName: String?, glob: GlobDescriptor, subdirPattern: String?
        ): SkyKey? {
            if (subdirPattern == null) {
                if (glob.globberOperation() === Globber.Operation.FILES) {
                    return null
                }

                // For FILES_AND_DIRS and SUBPACKAGES we want to maybe inspect a
                // PackageLookupValue for it.
                return key(
                    PackageIdentifier.create(
                        glob.getPackageId().getRepository(),
                        glob.getPackageId()
                            .getPackageFragment()
                            .getRelative(glob.getSubdir())
                            .getRelative(fileName)
                    )
                )
            } else {
                // There is some more pattern to match. Get the glob for the subdirectory. Note that this
                // directory may also match directly in the case of a pattern that starts with "**", but that
                // match will be found in the subdirectory glob.
                return GlobValue.Companion.internalKey(
                    glob.getPackageId(),
                    glob.getPackageRoot(),
                    glob.getSubdir().getRelative(fileName),
                    subdirPattern,
                    glob.globberOperation()
                )
            }
        }

        /**
         * Returns an Object indicating a match was found for the given fileName in the given
         * valueRequested. The Object will be one of:
         * 
         * 
         *  * `null` if no matches for the given parameters exists
         *  * `NestedSet<PathFragment>` if a match exists, either because we are looking for
         * files/directories or the SkyValue is a package and we're globbing for [       ][Globber.Operation.SUBPACKAGES]
         * 
         * 
         * 
         * `valueRequested` must be the SkyValue whose key was returned by [ ][.getSkyKeyForSubdir] for these parameters.
         */
        private fun getSubdirMatchesFromSkyValue(
            fileName: String?, glob: GlobDescriptor, valueRequested: SkyValue?
        ): Any? {
            if (valueRequested is GlobValue) {
                return (valueRequested as GlobValueWithNestedSet).getMatchesInNestedSet()
            }

            com.google.common.base.Preconditions.checkState(
                valueRequested is PackageLookupValue,
                "%s is not a GlobValue or PackageLookupValue (%s %s)",
                valueRequested,
                fileName,
                glob
            )

            val packageLookupValue: PackageLookupValue = valueRequested as PackageLookupValue
            if (packageLookupValue
                        is IncorrectRepositoryReferencePackageLookupValue
            ) {
                // This is a separate repository, so ignore it.
                return null
            }

            val isSubpackagesOp = glob.globberOperation() === Globber.Operation.SUBPACKAGES
            val pkgExists: Boolean = packageLookupValue.packageExists()

            if (!isSubpackagesOp && pkgExists) {
                // We're in our repo and fileName is a package. Since we're not doing SUBPACKAGES listing, we
                // do not want to add it to the results.
                return null
            } else if (isSubpackagesOp && !pkgExists) {
                // We're in our repo and the package exists. Since we're doing SUBPACKAGES listing, we do
                // want to add fileName to the results.
                return null
            }

            // The  fileName should be added to the results of the glob.
            return glob.getSubdir().getRelative(fileName)
        }
    }
}
