// Copyright 2016 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.buildtool

import com.google.common.flogger.GoogleLogger
import com.google.devtools.build.lib.cmdline.IgnoredSubdirectories
import com.google.devtools.build.lib.cmdline.LabelConstants
import com.google.devtools.build.lib.cmdline.PackageIdentifier
import com.google.devtools.build.lib.cmdline.RepositoryName
import com.google.devtools.build.lib.util.AbruptExitException
import com.google.devtools.build.lib.vfs.PathFragment
import com.google.devtools.build.lib.vfs.Root
import java.io.IOException
import java.util.Collections
import java.util.HashMap
import java.util.HashSet
import java.util.LinkedHashMap
import java.util.LinkedHashSet

/** Creates a symlink forest based on a package path map.  */
class SymlinkForest @kotlin.jvm.JvmOverloads constructor(
    packageRoots: com.google.common.collect.ImmutableMap<PackageIdentifier?, Root?>,
    execroot: com.google.devtools.build.lib.vfs.Path,
    productName: String?,
    siblingRepositoryLayout: Boolean = false
) {
    private val packageRoots: com.google.common.collect.ImmutableMap<PackageIdentifier?, Root?>
    private val execroot: com.google.devtools.build.lib.vfs.Path
    private val productName: String?
    private val prefix: String
    private val siblingRepositoryLayout: Boolean

    /**
     * Constructor for a symlink forest creator; does not perform any i/o.
     * 
     * 
     * Use [.plantSymlinkForest] to actually create the symlink forest.
     * 
     * @param packageRoots source package roots to which to create symlinks
     * @param execroot path where to plant the symlink forest
     * @param productName `BlazeRuntime#getProductName()`
     */
    /** Constructor for a symlink forest creator without non-symlinked directories parameter.  */
    init {
        this.packageRoots = packageRoots
        this.execroot = execroot
        this.productName = productName
        this.prefix = productName + "-"
        this.siblingRepositoryLayout = siblingRepositoryLayout
    }

    @Throws(IOException::class)
    private fun plantSymlinkForExternalRepo(
        plantedSymlinks: com.google.common.collect.ImmutableList.Builder<com.google.devtools.build.lib.vfs.Path?>,
        repository: RepositoryName,
        source: com.google.devtools.build.lib.vfs.Path?,
        externalRepoLinks: MutableSet<com.google.devtools.build.lib.vfs.Path?>
    ) {
        val plantedSymlink: java.util.Optional<com.google.devtools.build.lib.vfs.Path?> =
            plantSingleSymlinkForExternalRepo(
                repository, source, execroot, siblingRepositoryLayout, externalRepoLinks
            )
        plantedSymlink.ifPresent(java.util.function.Consumer { element: com.google.devtools.build.lib.vfs.Path? ->
            plantedSymlinks.add(
                element
            )
        })
    }

    @Throws(IOException::class)
    private fun plantSymlinkForestWithFullMainRepository(
        plantedSymlinks: com.google.common.collect.ImmutableList.Builder<com.google.devtools.build.lib.vfs.Path?>,
        mainRepoRoot: com.google.devtools.build.lib.vfs.Path
    ) {
        // For the main repo top-level directory, generate symlinks to everything in the directory
        // instead of the directory itself.
        if (siblingRepositoryLayout) {
            execroot.createDirectory()
        }
        for (target in mainRepoRoot.getDirectoryEntries()) {
            val baseName: String = target.getBaseName()
            val execPath: com.google.devtools.build.lib.vfs.Path = execroot.getRelative(baseName)
            if (symlinkShouldBePlanted(prefix, siblingRepositoryLayout, baseName, target)) {
                execPath.createSymbolicLink(target)
                plantedSymlinks.add(execPath)
                // TODO(jingwen-external): is this creating execroot/io_bazel/external?
            }
        }
    }

    @Throws(IOException::class, AbruptExitException::class)
    private fun plantSymlinkForestWithPartialMainRepository(
        plantedSymlinks: com.google.common.collect.ImmutableList.Builder<com.google.devtools.build.lib.vfs.Path?>,
        mainRepoLinks: MutableMap<com.google.devtools.build.lib.vfs.Path?, com.google.devtools.build.lib.vfs.Path?>
    ) {
        if (siblingRepositoryLayout) {
            execroot.createDirectory()
        }
        for (entry in mainRepoLinks.entrySet()) {
            val link: com.google.devtools.build.lib.vfs.Path = entry.getKey()
            val target: com.google.devtools.build.lib.vfs.Path? = entry.getValue()
            link.createSymbolicLink(target)
            plantedSymlinks.add(link)
        }
    }

    @Throws(IOException::class)
    private fun plantSymlinkForestMultiPackagePath(
        plantedSymlinks: com.google.common.collect.ImmutableList.Builder<com.google.devtools.build.lib.vfs.Path?>,
        packageRootsForMainRepo: MutableMap<PackageIdentifier?, Root>
    ) {
        // Packages come from exactly one root, but their shared ancestors may come from more.
        val dirRootsMap: MutableMap<PackageIdentifier?, MutableSet<Root>> =
            HashMap<PackageIdentifier?, MutableSet<Root>>()
        // Elements in this list are added so that parents come before their children.
        val dirsParentsFirst: java.util.ArrayList<PackageIdentifier> = java.util.ArrayList<PackageIdentifier>()
        for (entry in packageRootsForMainRepo.entrySet()) {
            val pkgId: PackageIdentifier = entry.getKey()
            val pkgRoot: Root? = entry.getValue()
            val newDirs: java.util.ArrayList<PackageIdentifier?> = java.util.ArrayList<PackageIdentifier?>()
            var fragment: PathFragment? = pkgId.getPackageFragment()
            while (!fragment.isEmpty()
            ) {
                val dirId: PackageIdentifier? = createInRepo(pkgId, fragment)
                var roots: MutableSet<Root?>? = dirRootsMap.get(dirId)
                if (roots == null) {
                    roots = HashSet<Root?>()
                    dirRootsMap.put(dirId, roots)
                    newDirs.add(dirId)
                }
                roots!!.add(pkgRoot)
                fragment = fragment.getParentDirectory()
            }
            Collections.reverse(newDirs)
            dirsParentsFirst.addAll(newDirs)
        }
        // Now add in roots for all non-pkg dirs that are in between two packages, and missed above.
        for (dir in dirsParentsFirst) {
            if (!packageRootsForMainRepo.containsKey(dir)) {
                val pkgId: PackageIdentifier? = longestPathPrefix(dir, packageRootsForMainRepo.keySet())
                if (pkgId != null) {
                    dirRootsMap.get(dir)!!.add(packageRootsForMainRepo.get(pkgId))
                }
            }
        }
        // Create output dirs for all dirs that have more than one root and need to be split.
        for (dir in dirsParentsFirst) {
            if (!dir.getRepository().isMain()) {
                execroot
                    .getRelative(dir.getRepository().getExecPath(siblingRepositoryLayout))
                    .createDirectoryAndParents()
            }
            if (dirRootsMap.get(dir).size() > 1) {
                logger.atFiner().log(
                    "mkdir %s", execroot.getRelative(dir.getExecPath(siblingRepositoryLayout))
                )
                execroot.getRelative(dir.getExecPath(siblingRepositoryLayout)).createDirectoryAndParents()
            }
        }

        // Make dir links for single rooted dirs.
        for (dir in dirsParentsFirst) {
            val roots: MutableSet<Root> = dirRootsMap.get(dir)
            // Simple case of one root for this dir.
            if (roots.size() == 1) {
                val parent: PathFragment? = dir.getPackageFragment().getParentDirectory()
                if (!parent.isEmpty() && dirRootsMap.get(createInRepo(dir, parent)).size() == 1) {
                    continue  // skip--an ancestor will link this one in from above
                }
                // This is the top-most dir that can be linked to a single root. Make it so.
                val root: Root = roots.iterator().next() // lone root in set
                val link: com.google.devtools.build.lib.vfs.Path =
                    execroot.getRelative(dir.getExecPath(siblingRepositoryLayout))
                logger.atFiner().log("ln -s %s %s", root.getRelative(dir.getSourceRoot()), link)
                link.createSymbolicLink(root.getRelative(dir.getSourceRoot()))
                plantedSymlinks.add(link)
            }
        }
        // Make links for dirs within packages, skip parent-only dirs.
        for (dir in dirsParentsFirst) {
            if (dirRootsMap.get(dir).size() > 1) {
                // If this dir is at or below a package dir, link in its contents.
                val pkgId: PackageIdentifier? = longestPathPrefix(dir, packageRootsForMainRepo.keySet())
                if (pkgId != null) {
                    val root: Root = packageRootsForMainRepo.get(pkgId)
                    try {
                        val absdir: com.google.devtools.build.lib.vfs.Path = root.getRelative(dir.getSourceRoot())
                        if (absdir.isDirectory()) {
                            logger.atFiner().log(
                                "ln -s %s/* %s/", absdir, execroot.getRelative(dir.getSourceRoot())
                            )
                            for (target in absdir.getDirectoryEntries()) {
                                val p: PathFragment? = root.relativize(target)
                                if (!dirRootsMap.containsKey(createInRepo(pkgId, p))) {
                                    execroot.getRelative(p).createSymbolicLink(target)
                                    plantedSymlinks.add(execroot.getRelative(p))
                                }
                            }
                        } else {
                            logger.atFine().log("Symlink planting skipping dir '%s'", absdir)
                        }
                    } catch (e: IOException) {
                        // TODO(arostovtsev): Why are we swallowing the IOException here instead of letting it
                        // be thrown?
                        logger.atWarning().withCause(e).log(
                            "I/O error while planting symlinks to contents of '%s'",
                            root.getRelative(dir.getSourceRoot())
                        )
                    }
                    // Otherwise its just an otherwise empty common parent dir.
                }
            }
        }

        for (entry in packageRootsForMainRepo.entrySet()) {
            val pkgId: PackageIdentifier = entry.getKey()
            if (pkgId.getPackageFragment() != PathFragment.EMPTY_FRAGMENT) {
                continue
            }
            val execrootDirectory: com.google.devtools.build.lib.vfs.Path =
                execroot.getRelative(pkgId.getExecPath(siblingRepositoryLayout))
            // If there were no subpackages, this directory might not exist yet.
            if (!execrootDirectory.exists()) {
                execrootDirectory.createDirectoryAndParents()
            }
            // For the top-level directory, generate symlinks to everything in the directory instead of
            // the directory itself.
            val sourceDirectory: com.google.devtools.build.lib.vfs.Path =
                entry.getValue().getRelative(pkgId.getSourceRoot())
            for (target in sourceDirectory.getDirectoryEntries()) {
                val baseName: String = target.getBaseName()
                val execPath: com.google.devtools.build.lib.vfs.Path = execrootDirectory.getRelative(baseName)
                // Create any links that don't exist yet and don't start with bazel-.
                if (!baseName.startsWith(productName + "-") && !execPath.exists()) {
                    execPath.createSymbolicLink(target)
                    plantedSymlinks.add(execPath)
                }
            }
        }
    }

    /**
     * Performs the filesystem operations to plant the symlink forest.
     * 
     * @return the symlinks that have been planted
     */
    @Throws(IOException::class, AbruptExitException::class)
    fun plantSymlinkForest(): com.google.common.collect.ImmutableList<com.google.devtools.build.lib.vfs.Path?> {
        deleteTreesBelowNotPrefixed(execroot, prefix)
        deleteSiblingRepositorySymlinks(siblingRepositoryLayout, execroot)

        var shouldLinkAllTopLevelItems = false
        val mainRepoLinks: MutableMap<com.google.devtools.build.lib.vfs.Path?, com.google.devtools.build.lib.vfs.Path?> =
            LinkedHashMap<com.google.devtools.build.lib.vfs.Path?, com.google.devtools.build.lib.vfs.Path?>()
        val mainRepoRoots: MutableSet<Root?> = LinkedHashSet<Root?>()
        val externalRepoLinks: MutableSet<com.google.devtools.build.lib.vfs.Path?> =
            LinkedHashSet<com.google.devtools.build.lib.vfs.Path?>()
        val packageRootsForMainRepo: MutableMap<PackageIdentifier?, Root> = LinkedHashMap<PackageIdentifier?, Root>()
        val plantedSymlinks: com.google.common.collect.ImmutableList.Builder<com.google.devtools.build.lib.vfs.Path?> =
            com.google.common.collect.ImmutableList.builder<com.google.devtools.build.lib.vfs.Path?>()

        for (entry in packageRoots.entrySet()) {
            val pkgId: PackageIdentifier = entry.getKey()
            if (pkgId == LabelConstants.EXTERNAL_PACKAGE_IDENTIFIER) {
                // //external is a virtual package regardless , don't add it to the symlink tree.
                // Subpackages of //external, like //external/foo, are fine though.
                continue
            }
            val repository: RepositoryName = pkgId.getRepository()
            if (repository.isMain()) {
                // Record main repo packages.
                packageRootsForMainRepo.put(entry.getKey(), entry.getValue())

                // Record the root of the packages.
                mainRepoRoots.add(entry.getValue())

                // For single root (single package path) case:
                // If root package of the main repo is required, we record the main repo root so that
                // we can later link everything under the main repo's top-level directory.
                // If root package of the main repo is not required, we only record links for
                // directories under the top-level directory that are used in required packages.
                if (pkgId.getPackageFragment() == PathFragment.EMPTY_FRAGMENT) {
                    shouldLinkAllTopLevelItems = true
                } else {
                    val baseName: String = pkgId.getPackageFragment().getSegment(0)
                    if (!siblingRepositoryLayout
                        && baseName == LabelConstants.EXTERNAL_PATH_PREFIX.getBaseName()
                    ) {
                        // ignore external/ directory if user has it in the source tree
                        // because it conflicts with external repository location.
                        continue
                    }
                    val execrootLink: com.google.devtools.build.lib.vfs.Path = execroot.getRelative(baseName)
                    val sourcePath: com.google.devtools.build.lib.vfs.Path? =
                        entry.getValue().getRelative(pkgId.getTopLevelDir())
                    mainRepoLinks.putIfAbsent(execrootLink, sourcePath)
                }
            } else {
                plantSymlinkForExternalRepo(
                    plantedSymlinks, repository, entry.getValue().asPath(), externalRepoLinks
                )
            }
        }

        // TODO(bazel-team): Bazel can find packages in multiple paths by specifying --package_paths,
        // we need a more complex algorithm to build execroot in that case. As --package_path will be
        // removed in the future, we should remove the plantSymlinkForestMultiPackagePath
        // implementation when --package_path is gone.
        if (mainRepoRoots.size() > 1) {
            plantSymlinkForestMultiPackagePath(plantedSymlinks, packageRootsForMainRepo)
        } else if (shouldLinkAllTopLevelItems) {
            val mainRepoRoot: com.google.devtools.build.lib.vfs.Path? =
                com.google.common.collect.Iterables.getOnlyElement<Root?>(mainRepoRoots).asPath()
            plantSymlinkForestWithFullMainRepository(plantedSymlinks, mainRepoRoot)
        } else {
            plantSymlinkForestWithPartialMainRepository(plantedSymlinks, mainRepoLinks)
        }

        logger.atInfo().log("Planted symlink forest in %s", execroot)
        return plantedSymlinks.build()
    }

    /** Checked exception for issues with Symlink planting.  */
    class SymlinkPlantingException(msg: String?, e: IOException?) : java.lang.Exception(msg, e)
    companion object {
        private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()

        /**
         * Returns the longest prefix from a given set of 'prefixes' that are contained in 'path'. I.e the
         * closest ancestor directory containing path. Returns null if none found.
         * 
         * @param path
         * @param prefixes
         */
        @com.google.common.annotations.VisibleForTesting
        fun longestPathPrefix(
            path: PackageIdentifier, prefixes: MutableSet<PackageIdentifier?>
        ): PackageIdentifier? {
            for (i in path.getPackageFragment().segmentCount() downTo 0) {
                val prefix: PackageIdentifier? = createInRepo(path, path.getPackageFragment().subFragment(0, i))
                if (prefixes.contains(prefix)) {
                    return prefix
                }
            }
            return null
        }

        /**
         * Delete all dir trees under a given 'dir' that don't start with a given 'prefix', and is not
         * special case of not symlinked to exec root directories (those directories are special case of
         * output roots, so they must be kept before commands). Does not follow any symbolic links.
         */
        @com.google.common.annotations.VisibleForTesting
        @com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe
        @Throws(IOException::class)
        fun deleteTreesBelowNotPrefixed(dir: com.google.devtools.build.lib.vfs.Path, prefix: String?) {
            for (p in dir.getDirectoryEntries()) {
                if (p.getBaseName().startsWith(prefix)) {
                    continue
                }

                p.deleteTree()
            }
        }

        @Throws(IOException::class)
        private fun deleteSiblingRepositorySymlinks(
            siblingRepositoryLayout: Boolean, execroot: com.google.devtools.build.lib.vfs.Path
        ) {
            if (siblingRepositoryLayout) {
                // Delete execroot/../<symlinks> to directories representing external repositories.
                for (p in execroot.getParentDirectory().getDirectoryEntries()) {
                    if (p.isSymbolicLink()) {
                        p.deleteTree()
                    }
                }
            }
        }

        /**
         * Eagerly plant the symlinks from execroot to the source root provided by the single package path
         * of the current build. Only works with a single package path. Before planting the new symlinks,
         * remove all existing symlinks in execroot which don't match certain criteria.
         * 
         * 
         * It's possible to have a conflict here. For example when we plant symlinks form a
         * case-insensitive FS to a case-sensitive one.
         * 
         * @return a set of potentially conflicting baseNames, all in lowercase.
         */
        @Throws(IOException::class)
        fun eagerlyPlantSymlinkForestSinglePackagePath(
            execroot: com.google.devtools.build.lib.vfs.Path,
            sourceRoot: com.google.devtools.build.lib.vfs.Path,
            prefix: String?,
            ignoredPaths: IgnoredSubdirectories,
            siblingRepositoryLayout: Boolean
        ): com.google.common.collect.ImmutableSet<String?> {
            deleteTreesBelowNotPrefixed(execroot, prefix)
            deleteSiblingRepositorySymlinks(siblingRepositoryLayout, execroot)

            val symlinkBaseNameToTargets: MutableMap<String?, MutableList<com.google.devtools.build.lib.vfs.Path?>?> =
                HashMap<String?, MutableList<com.google.devtools.build.lib.vfs.Path?>?>()
            val potentiallyConflictingBaseNamesLowercase: MutableSet<String?> = HashSet<String?>()
            for (target in sourceRoot.getDirectoryEntries()) {
                val baseNameLowercase: String = com.google.common.base.Ascii.toLowerCase(target.getBaseName())
                symlinkBaseNameToTargets
                    .computeIfAbsent(
                        baseNameLowercase,
                        java.util.function.Function { x: String? -> java.util.ArrayList<com.google.devtools.build.lib.vfs.Path?>() })
                    .add(target)
            }

            for (entry in symlinkBaseNameToTargets.entrySet()) {
                val baseNameLowercase: String? = entry.getKey()
                val targets: MutableList<com.google.devtools.build.lib.vfs.Path?> = entry.getValue()
                // Easy case: there's no clashing expected. Just plant with the ORIGINAL base name.
                if (targets.size() == 1) {
                    val target: com.google.devtools.build.lib.vfs.Path? =
                        com.google.common.collect.Iterables.getOnlyElement<com.google.devtools.build.lib.vfs.Path?>(
                            targets
                        )
                    val originalBaseName: String = target.getBaseName()
                    val link: com.google.devtools.build.lib.vfs.Path = execroot.getRelative(originalBaseName)
                    if (symlinkShouldBePlanted(
                            prefix, ignoredPaths, siblingRepositoryLayout, originalBaseName, target
                        )
                    ) {
                        link.createSymbolicLink(target)
                    }
                } else {
                    potentiallyConflictingBaseNamesLowercase.add(baseNameLowercase)
                }
            }
            return com.google.common.collect.ImmutableSet.copyOf<String?>(potentiallyConflictingBaseNamesLowercase)
        }

        fun symlinkShouldBePlanted(
            prefix: String?,
            siblingRepositoryLayout: Boolean,
            baseName: String,
            target: com.google.devtools.build.lib.vfs.Path
        ): Boolean {
            return symlinkShouldBePlanted(
                prefix, IgnoredSubdirectories.Companion.EMPTY, siblingRepositoryLayout, baseName, target
            )
        }

        fun symlinkShouldBePlanted(
            prefix: String?,
            ignoredSubdirectories: IgnoredSubdirectories,
            siblingRepositoryLayout: Boolean,
            baseName: String,
            target: com.google.devtools.build.lib.vfs.Path
        ): Boolean {
            // Create any links that don't start with bazel-, and ignore external/ directory if
            // user has it in the source tree because it conflicts with external repository location.
            return !baseName.startsWith(prefix) && ignoredSubdirectories.matchingEntry(
                target.asFragment().toRelative()
            ) == null && (siblingRepositoryLayout
                    || baseName != LabelConstants.EXTERNAL_PATH_PREFIX.getBaseName())
        }

        /**
         * Performs the planting of a symlink to an external repository.
         * 
         * @return the planted symlink, or an empty optional if nothing was planted.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        @Throws(IOException::class)
        fun plantSingleSymlinkForExternalRepo(
            repository: RepositoryName,
            source: com.google.devtools.build.lib.vfs.Path?,
            execroot: com.google.devtools.build.lib.vfs.Path,
            siblingRepositoryLayout: Boolean,
            alreadyPlantedExternalRepoLinks: MutableSet<com.google.devtools.build.lib.vfs.Path?>
        ): java.util.Optional<com.google.devtools.build.lib.vfs.Path?> {
            // For external repositories, create one symlink to each external repository
            // directory.
            // From <output_base>/execroot/<main repo name>/external/<external repo name>
            // to   <output_base>/external/<external repo name>
            //
            // However, if --experimental_sibling_repository_layout is true, symlink:
            // From <output_base>/execroot/<external repo name>
            // to   <output_base>/external/<external repo name>
            val execrootLink: com.google.devtools.build.lib.vfs.Path =
                execroot.getRelative(repository.getExecPath(siblingRepositoryLayout))

            if (!siblingRepositoryLayout && alreadyPlantedExternalRepoLinks.isEmpty()) {
                execroot.getRelative(LabelConstants.EXTERNAL_PATH_PREFIX).createDirectoryAndParents()
            }
            // Prevent re-creating existing symlinks.
            if (!alreadyPlantedExternalRepoLinks.add(execrootLink)) {
                return java.util.Optional.empty<com.google.devtools.build.lib.vfs.Path?>()
            }
            execrootLink.createSymbolicLink(source)
            return java.util.Optional.of<com.google.devtools.build.lib.vfs.Path?>(execrootLink)
        }

        private fun createInRepo(
            repo: PackageIdentifier, packageFragment: PathFragment?
        ): PackageIdentifier? {
            return PackageIdentifier.Companion.create(repo.getRepository(), packageFragment)
        }
    }
}
