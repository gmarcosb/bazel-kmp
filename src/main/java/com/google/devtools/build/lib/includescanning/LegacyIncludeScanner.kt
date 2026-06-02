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
package com.google.devtools.build.lib.includescanning

import com.google.common.base.Preconditions
import com.google.common.collect.ImmutableList
import com.google.common.collect.ImmutableSet
import com.google.common.collect.Sets
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.google.devtools.build.lib.actions.ActionExecutionContext
import com.google.devtools.build.lib.concurrent.AbstractQueueVisitor
import com.google.devtools.build.lib.concurrent.ErrorClassifier
import com.google.devtools.build.lib.concurrent.ThreadSafety
import com.google.devtools.build.lib.vfs.Path
import java.util.Objects
import java.util.concurrent.Future
import java.util.function.Supplier
import kotlin.collections.ArrayList
import kotlin.collections.MutableCollection
import kotlin.collections.MutableIterator
import kotlin.collections.MutableList
import kotlin.collections.MutableSet

/**
 * C include scanner. Quickly scans C/C++ source files to determine the bounding set of transitively
 * referenced include files.
 * 
 * 
 * Maintains caches for parses and search-matches for performance.
 * 
 * <pre>
 * TODO(bazel-team): (2009) Currently does not evaluate preprocessor symbols, so computed includes
 * are ignored.
 * TODO(bazel-team): (2009) Does not handle multiline block comments preceding or around an #include
</pre> * 
 */
open class LegacyIncludeScanner internal constructor(
    private val parser: IncludeParser,
    includePool: ExecutorService?,
    shouldShuffle: Boolean,
    cache: ConcurrentMap<Artifact?, ListenableFuture<MutableCollection<Inclusion?>?>?>,
    pathCache: PathExistenceCache,
    quoteIncludePaths: MutableList<PathFragment?>,
    includePaths: MutableList<PathFragment?>,
    frameworkIncludePaths: MutableList<PathFragment?>,
    outputPath: Path,
    execRoot: Path,
    artifactFactory: ArtifactFactory?,
    spawnIncludeScannerSupplier: Supplier<SpawnIncludeScanner?>
) : IncludeScanner {
    private class ArtifactWithInclusionContext(
        artifact: Artifact,
        contextKind: IncludeParser.Inclusion.Kind?,
        contextPathPos: Int
    ) {
        private val artifact: Artifact
        private val contextKind: IncludeParser.Inclusion.Kind?
        private val contextPathPos: Int

        init {
            this.artifact = artifact
            this.contextKind = contextKind
            this.contextPathPos = contextPathPos
        }

        override fun hashCode(): Int {
            return contextPathPos + 37 * Objects.hash(contextKind, artifact)
        }

        override fun equals(obj: Any?): Boolean {
            if (this === obj) {
                return true
            }
            if (obj !is ArtifactWithInclusionContext) {
                return false
            }
            return this.contextKind == obj.contextKind && this.contextPathPos == obj.contextPathPos && this.artifact.equals(
                obj.artifact
            )
        }
    }

    /**
     * A cache of inclusion lookups, taking care to avoid spurious caching related to generated
     * headers / source files.
     */
    @ThreadSafety.ThreadSafe
    private inner class InclusionCache {
        private val cache: ConcurrentMap<InclusionWithContext?, LocateOnPathResult?> =
            ConcurrentHashMap<InclusionWithContext?, LocateOnPathResult?>()

        /**
         * Locates an included file along the search paths. The result is cacheable.
         * 
         * @param inclusion the inclusion to locate
         * @param onlyCheckGenerated if true, only search for generated output files
         * @return a tuple of the found file, the position of the respective include path entry on the
         * search path (or null if no matching file was found), and whether the scan touched illegal
         * output files
         */
        fun locateOnPaths(
            inclusion: InclusionWithContext,
            headerData: IncludeScanningHeaderData,
            onlyCheckGenerated: Boolean
        ): LocateOnPathResult {
            val name: PathFragment = inclusion.getInclusion().pathFragment

            // A framework header must begin with a framework name, followed by a path separator, followed
            // by the rest of the header path.  We do not currently support include_next of framework
            // headers.
            val searchFrameworkIncludePaths =
                !frameworkIncludePaths.isEmpty() && !inclusion.getInclusion().kind.isNext() && !name.containsUplevelReferences() && PathFragment.containsSeparator(
                    name.getPathString()
                )

            // For #include_next directives we start searching on the include path where
            // we found the previous inclusion.
            val searchStart = if (inclusion.getInclusion().kind.isNext()) inclusion.getContextPathPos() else 0

            // Search the header on the remaining paths.
            val paths: MutableList<PathFragment?> =
                if (inclusion.getContextKind() == IncludeParser.Inclusion.Kind.QUOTE) quoteIncludePaths else includePaths
            var alsoSearchFrameworkAtIndex =
                if (inclusion.getContextKind() == IncludeParser.Inclusion.Kind.QUOTE) quoteIncludePathsFrameworkIndex else 0
            alsoSearchFrameworkAtIndex = Math.max(alsoSearchFrameworkAtIndex, searchStart)
            var viewedIllegalOutput = false
            for (i in searchStart..<paths.size()) {
                if (i == alsoSearchFrameworkAtIndex && searchFrameworkIncludePaths) {
                    val frameworkName = name.subFragment(0, 1).getPathString() + ".framework"
                    val relHeaderPath: PathFragment = name.subFragment(1)
                    val result =
                        locateOnFrameworkPaths(
                            frameworkName,
                            relHeaderPath,
                            headerData,
                            onlyCheckGenerated,
                            viewedIllegalOutput
                        )
                    if (result.path != null) {
                        return result
                    }
                    viewedIllegalOutput = viewedIllegalOutput || result.viewedIllegalOutputFile
                }
                var fileFragment: PathFragment = paths.get(i).getRelative(name)
                if (fileFragment.containsUplevelReferences()) {
                    // TODO(janakr): This branch shouldn't be necessary: we should be able to filter such
                    // inclusions out unconditionally.
                    // Deal with fragments that escape the execroot. They most likely come right back in.
                    val execRootRelativePath: Path = execRoot.getRelative(fileFragment)
                    if (execRootRelativePath.startsWith(execRoot)) {
                        // Common case: transform #include "../execroot/foo.h" into #include "foo.h"
                        fileFragment = execRootRelativePath.relativeTo(execRoot)
                    } else {
                        // Ugh: successfully escaped the exec root. It's their funeral.
                        fileFragment = execRootRelativePath.asFragment()
                    }
                    // This can happen when we are processing Windows paths with backslashes on Unix,
                    // since we do not do any #ifdef processing.
                    // We can safely discard these here.
                    if (fileFragment.containsUplevelReferences()) {
                        continue
                    }
                }
                if (onlyCheckGenerated && !isOutputFile(fileFragment)) {
                    continue
                }
                viewedIllegalOutput = viewedIllegalOutput || isIllegalOutputFile(fileFragment, headerData)
                val isOutputDirectory: Boolean = fileFragment.startsWith(outputPathFragment)
                if (!isFile(fileFragment, name, !isOutputDirectory, headerData)) {
                    continue
                }
                val artifact: Artifact?
                if (isOutputDirectory) {
                    // May be a normal output file or an inc_library header.
                    artifact = headerData.getHeaderArtifact(fileFragment)
                    if (artifact == null) {
                        // This happens if an included file exists in a cc_inc_library's output directory,
                        // but is not an output of the cc_inc_library. This can happen if, for instance, the
                        // definition of the cc_inc_library is changed to output different files, but the
                        // source file's includes don't change.
                        // Often, such an include is conditional, and so failing to find it here will not
                        // lead to problems. If this include is actually needed for compilation, then we will
                        // emit a somewhat unhelpful error message of a missing file, rather than the more
                        // helpful one of an illegal include, but it's hard to emit the illegal include
                        // message consistently, and this is a rare occurrence in any case.
                        return LocateOnPathResult.Companion.createNotFound(viewedIllegalOutput)
                    }
                } else if (!fileFragment.isAbsolute()) {
                    artifact = artifactFactory.resolveSourceArtifact(fileFragment, RepositoryName.MAIN)
                    if (artifact == null) {
                        // There was a real file, but we couldn't resolve it, probably because it belonged to
                        // a package that wasn't actually loaded this build, so user cannot refer to files in
                        // that package.
                        continue
                    }
                } else {
                    // This file is given with an absolute path. We will error out after transitive scanning
                    // of the top-level source is finished unless this corresponds to a built-in include
                    // directory, and will ignore this artifact in any case, but track it here so that its
                    // includes can be processed.
                    artifact = artifactFactory.getSourceArtifact(fileFragment, absoluteRoot)
                }
                // +1 to account for the virtual entry for relative includes.
                return LocateOnPathResult.Companion.create(artifact, i + 1, viewedIllegalOutput)
            }

            // Not found.
            return LocateOnPathResult.Companion.createNotFound(viewedIllegalOutput)
        }

        /**
         * Locates an included file along the framework search paths. The result is cacheable.
         * 
         * @param frameworkName the name of the framework, including the ".framework" suffix
         * @param relHeaderPath the path of the framework header, relative to the framework
         * @param onlyCheckGenerated if true, only search for generated output files
         * @param viewedIllegalOutput whether the scanner has viewed an illegal output file.
         * @return a tuple of the found file, the context path position of the input inclusion, and
         * whether the scan touched illegal output files
         */
        fun locateOnFrameworkPaths(
            frameworkName: String?,
            relHeaderPath: PathFragment,
            headerData: IncludeScanningHeaderData,
            onlyCheckGenerated: Boolean,
            viewedIllegalOutput: Boolean
        ): LocateOnPathResult {
            var viewedIllegalOutput = viewedIllegalOutput
            for (i in frameworkIncludePaths.indices) {
                val includePath: PathFragment = frameworkIncludePaths.get(i)

                // Construct the full framework path path/to/foo.framework.
                val fullFrameworkPath: PathFragment = includePath.getRelative(frameworkName)

                if (onlyCheckGenerated && !isOutputFile(fullFrameworkPath)) {
                    return LocateOnPathResult.Companion.createNotFound(viewedIllegalOutput)
                }

                // Look for header in path/to/foo.framework/Headers/
                val foundHeaderPath: PathFragment
                var fullHeaderPath: PathFragment =
                    fullFrameworkPath.getRelative("Headers").getRelative(relHeaderPath)

                viewedIllegalOutput =
                    viewedIllegalOutput || isIllegalOutputFile(fullHeaderPath, headerData)
                val isOutputDirectory: Boolean = fullHeaderPath.startsWith(outputPathFragment)
                if (isFile(fullHeaderPath, relHeaderPath, isOutputDirectory, headerData)) {
                    foundHeaderPath = fullHeaderPath
                } else {
                    // Look for header in path/to/foo.framework/PrivateHeaders/
                    fullHeaderPath =
                        fullFrameworkPath.getRelative("PrivateHeaders").getRelative(relHeaderPath)
                    viewedIllegalOutput =
                        viewedIllegalOutput || isIllegalOutputFile(fullHeaderPath, headerData)
                    if (isFile(fullHeaderPath, relHeaderPath, isOutputDirectory, headerData)) {
                        foundHeaderPath = fullHeaderPath
                    } else {
                        continue
                    }
                }

                val artifact: Artifact?
                if (isOutputDirectory) {
                    artifact = headerData.getHeaderArtifact(foundHeaderPath)
                    if (artifact == null) {
                        // This happens if an included file exists in a framework directory but is not but is
                        // not an output of the framework rule.
                        // Such an include may be conditional, and so failing to find it here will not lead to
                        // problems. If this include is actually needed for compilation, then we will emit a
                        // somewhat unhelpful error message of a missing file, rather than the more helpful one
                        // of an illegal include, but it's hard to emit the illegal include message
                        // consistently, and this is a rare occurrence in any case.

                        // Note that the corresponding case for non-framework paths aborts the search here, but
                        // for framdwork paths, we keep going like in other cases where we can't find a header
                        // we have access to.

                        continue
                    }
                } else if (!foundHeaderPath.isAbsolute()) {
                    artifact = artifactFactory.resolveSourceArtifact(foundHeaderPath, RepositoryName.MAIN)
                    if (artifact == null) {
                        // There was a real file, but we couldn't resolve it, probably because it belonged to
                        // a package that wasn't actually loaded this build, so user cannot refer to files in
                        // that package.
                        continue
                    }
                } else {
                    // This file is given with an absolute path. We will error out after transitive scanning
                    // of the top-level source is finished unless this corresponds to a built-in include
                    // directory, and will ignore this artifact in any case, but track it here so that its
                    // includes can be processed.
                    artifact = artifactFactory.getSourceArtifact(foundHeaderPath, absoluteRoot)
                }
                // Reset contextPathPos to 0 so that include_next in a framework header searches the include
                // paths from the beginning.
                return LocateOnPathResult.Companion.create(artifact, 0, viewedIllegalOutput)
            }
            // Not found.
            return LocateOnPathResult.Companion.createNotFound(viewedIllegalOutput)
        }

        /**
         * Locates an included file along the search paths.
         * 
         * @param inclusion the inclusion to locate
         * @return a LocateOnPathResult
         */
        fun lookup(
            inclusion: InclusionWithContext, headerData: IncludeScanningHeaderData
        ): LocateOnPathResult {
            var result = cache.get(inclusion)
            if (result == null) {
                // Do not use computeIfAbsent() as the implementation of locateOnPaths might do multiple
                // file stats and this creates substantial contention given CompactHashMap's locking.
                // Do not use futures as the few duplicate executions are cheaper than the additional memory
                // that would be required.
                result = locateOnPaths(inclusion, headerData, false)
                cache.put(inclusion, result)
                return result
            }
            // If the previous computation for this inclusion had a different pathToDeclaredHeader
            // map, result may not be valid for this lookup. Because this is a hot spot, we tolerate a
            // known correctness bug but try to catch most issues.
            // (1) [correct]: The prior computation found an output file, but that file is not in the
            // current lookup's inputs. We don't reuse the computation. b/149935208.
            // (2) [correct]: The prior computation checked an output path not in its legal outputs, and
            // then didn't find a file anywhere. However, that output file is a legal input for this
            // lookup. We don't reuse the computation. b/2097998.
            // (3) [INCORRECT]: Same as (2), except that the prior computation found a file after checking
            // the output path not in its legal inputs. We incorrectly cache this computation, assuming it
            // is very rare. b/150307245.
            if (result.path != null) {
                if (result.path.isSourceArtifact()
                    || result.path.equals(headerData.getHeaderArtifact(result.path.getExecPath()))
                ) {
                    return result
                }
            } else if (!result.viewedIllegalOutputFile) {
                return result
            }

            result = locateOnPaths(inclusion, headerData, true)
            if (result.path != null || !result.viewedIllegalOutputFile) {
                // In this case, the result is now cachable either because a file has been found or
                // because there are no more illegal output files. This is rare in practice. Avoid
                // creating a future and modifying the cache in the common case.
                cache.put(inclusion, result)
            }
            return result
        }
    }

    private class LocateOnPathResult(path: Artifact?, includePosition: Int, viewedIllegalOutputFile: Boolean) {
        private val path: Artifact?
        private val includePosition: Int
        private val viewedIllegalOutputFile: Boolean

        init {
            this.path = path
            this.includePosition = includePosition
            this.viewedIllegalOutputFile = viewedIllegalOutputFile
        }

        companion object {
            private val NOT_FOUND_VIEWED_ILLEGAL = LocateOnPathResult(null, -1, true)
            private val NOT_FOUND_NO_VIEWED_ILLEGAL = LocateOnPathResult(null, -1, false)
            fun create(
                path: Artifact?, includePosition: Int, viewedIllegalOutputFile: Boolean
            ): LocateOnPathResult {
                return LocateOnPathResult(
                    Preconditions.checkNotNull<Artifact?>(path), includePosition, viewedIllegalOutputFile
                )
            }

            fun createNotFound(viewedIllegalOutputFile: Boolean): LocateOnPathResult {
                return if (viewedIllegalOutputFile) NOT_FOUND_VIEWED_ILLEGAL else NOT_FOUND_NO_VIEWED_ILLEGAL
            }
        }
    }

    private val execRoot: Path

    private val artifactFactory: ArtifactFactory
    private val spawnIncludeScannerSupplier: Supplier<SpawnIncludeScanner?>

    /**
     * Externally-scoped cache of file path => parsed inclusion set mappings. Saves us from having to
     * parse files more than once, and can be shared by scanners with different search paths.
     */
    private val fileParseCache: ConcurrentMap<Artifact?, ListenableFuture<MutableCollection<Inclusion?>?>?>

    /**
     * Search path for searching for all quoted "xyz.h" includes, composed of all the -iquote, -I and
     * -isystem paths (in this order).
     */
    private val quoteIncludePaths: ImmutableList<PathFragment?>

    /**
     * The index position within quoteIncludePaths at which framework paths (-F) should be searched.
     */
    private val quoteIncludePathsFrameworkIndex: Int

    /**
     * Search path for searching for all includes, composed of all the -I and -isystem paths (in this
     * order).
     */
    private val includePaths: MutableList<PathFragment?>

    /** Search path for searching for all includes from frameworks.  */
    private val frameworkIncludePaths: ImmutableList<PathFragment>

    private val outputPathFragment: PathFragment
    private val absoluteRoot: Root?

    /**
     * Scanner-scoped cache of inclusions with their resolved files and include path entries. This
     * cache is specific to a given pair of search paths, and is thus scanner-local.
     * 
     * 
     * Each inclusion (name+type+context) is associated with its resolved file here, thus saving
     * redundant path searches. The second entry of the pair is the include path entry on which the
     * file was found.
     */
    private val inclusionCache: InclusionCache

    private val pathCache: PathExistenceCache

    private val includePool: ExecutorService?
    private val shouldShuffle: Boolean

    /**
     * Locates an included file relative to the including file. The result is not cacheable.
     * 
     * @param inclusion the inclusion to locate
     * @param includer the including file
     * @return the resolved Path, or null if no file could be found
     */
    private fun locateRelative(
        inclusion: Inclusion,
        headerData: IncludeScanningHeaderData,
        includer: Artifact,
        parent: PathFragment
    ): Artifact? {
        if (inclusion.kind != IncludeParser.Inclusion.Kind.QUOTE) {
            return null
        }
        val name: PathFragment = inclusion.pathFragment

        // The most effective way to see that something is not a relative inclusion is to see whether
        // the include statement starts with a directory (has a '/') and whether that directory exists.
        // We only do this for source files as we never match generated files against the file system.
        if (includer.isSourceArtifact() && !name.containsUplevelReferences()) {
            val firstSegment: String = name.getSegment(0)
            // Specifically avoiding a call to segmentCount() here as that would scan the entire path.
            if (firstSegment.length() < name.getPathString().length()
                && !pathCache.directoryExists(parent.getRelative(firstSegment))
            ) {
                return null
            }
        }
        val execPath: PathFragment = parent.getRelative(name)
        if (!isFile(execPath, name, includer.isSourceArtifact(), headerData)) {
            return null
        }
        val parentDirectory: PathFragment = includer.getRootRelativePath().getParentDirectory()
        val rootRelativePath: PathFragment = parentDirectory.getRelative(name)
        if (rootRelativePath.containsUplevelReferences()) {
            // An include cannot break out of a (package path) root via a relative inclusion. It should
            // also not break out of the root and then come back into it -- who knows what hardcoded
            // directory names there are in it.
            return null
        }
        val header: Artifact? = headerData.getHeaderArtifact(execPath)
        if (header != null) {
            return header
        }
        val root: ArtifactRoot? = includer.getRoot()
        val sourceArtifact: Artifact? =
            artifactFactory.resolveSourceArtifactWithAncestor(name, parent, root, RepositoryName.MAIN)
        if (sourceArtifact == null) {
            // If the name had up-level references, this path may not be under any package. Otherwise,
            // we must have gotten an artifact, since it should be under the same package as the
            // including artifact.
            Preconditions.checkState(
                name.containsUplevelReferences(), "%s %s %s %s", name, parent, rootRelativePath, root
            )
        }
        return sourceArtifact
    }

    /** Returns whether the given path exists in the filesystem.  */
    private fun isFile(
        execPath: PathFragment,
        includeAsWritten: PathFragment,
        isSource: Boolean,
        headerData: IncludeScanningHeaderData
    ): Boolean {
        if (isOutputFile(execPath)) {
            return headerData.isDeclaredHeader(execPath)
        }
        // TODO(djasper): This code path cannot be hit with isSource being false. Verify and add
        // Preconditions check.
        if (isSource && !execPath.isAbsolute() && execPath.endsWith(includeAsWritten)) {
            // Verify that the directory of execPath exists as an optimization. Most includes are relative
            // to the workspace and we'd like to avoid stat'ing every such include relative to every
            // include path. If testing whether "a/b/c.h" is a file beneath the include path "e/f/",
            // verify that "e/f/a" and "e/f/a/b" are valid directories (and cache the result).
            val execPathSegments: Int = execPath.segmentCount()
            val nameSegments: Int = includeAsWritten.segmentCount()
            for (i in execPathSegments - nameSegments + 1..<execPathSegments) {
                if (!pathCache.directoryExists(execPath.subFragment(0, i))) {
                    return false
                }
            }
        }
        // Shortcut: If this is a declared header, it's bound to exist.
        if (headerData.isDeclaredHeader(execPath)) {
            return true
        }
        return pathCache.fileExists(execPath, isSource)
    }

    @Throws(IOException::class, NoSuchPackageException::class, ExecException::class, InterruptedException::class)
    override fun processAsync(
        mainSource: Artifact?,
        sources: MutableCollection<Artifact>,
        includeScanningHeaderData: IncludeScanningHeaderData,
        cmdlineIncludes: MutableList<String?>,
        includes: MutableSet<Artifact>,
        actionExecutionMetadata: ActionExecutionMetadata?,
        actionExecutionContext: ActionExecutionContext,
        grepIncludes: Artifact?,
        grepIncludesExecutionPlatform: PlatformInfo?
    ) {
        val env: SkyFunction.Environment = actionExecutionContext.getEnvironmentForDiscoveringInputs()
        val pathHints: ImmutableSet<Artifact>?
        if (parser.getHints() == null) {
            pathHints = ImmutableSet.of<Artifact>()
        } else {
            pathHints = parser.getHints().getPathLevelHintedInclusions(quoteIncludePaths, env)
            if (env.valuesMissing()) {
                return
            }
            Preconditions.checkNotNull<ImmutableSet<Artifact?>?>(pathHints, "Null path hints for %s", quoteIncludePaths)
        }

        val visitor =
            IncludeVisitor(
                actionExecutionMetadata,
                actionExecutionContext,
                grepIncludes,
                grepIncludesExecutionPlatform,
                includeScanningHeaderData
            )

        try {
            visitor.processInternal(mainSource, sources, cmdlineIncludes, includes, pathHints)
        } catch (e: MissingDepExecException) {
            // This happens when a skyframe restart is necessary. Callers are responsible for checking
            // env.valuesMissing() as per this method's contract, so we can just ignore the exception.
            if (!env.valuesMissing()) {
                throw IllegalStateException("Missing dep without skyframe request", e)
            }
        }
    }

    private fun isIllegalOutputFile(
        includeFile: PathFragment, headerData: IncludeScanningHeaderData
    ): Boolean {
        return isOutputFile(includeFile) && !headerData.isDeclaredHeader(includeFile)
    }

    private fun isOutputFile(path: PathFragment): Boolean {
        return path.startsWith(outputPathFragment)
    }

    /**
     * Constructs a new IncludeScanner
     * 
     * @param cache externally scoped cache of file-path to inclusion-set mappings
     * @param pathCache include path existence cache
     * @param quoteIncludePaths the list of quote search path dirs (-iquote)
     * @param includePaths the list of all other non-framework search path dirs (-I and -isystem)
     * @param frameworkIncludePaths the list of framework other search path dirs (-F)
     */
    init {
        this.includePool = includePool
        this.shouldShuffle = shouldShuffle
        this.fileParseCache = cache
        this.pathCache = pathCache
        this.artifactFactory = Preconditions.checkNotNull<ArtifactFactory>(artifactFactory)
        this.spawnIncludeScannerSupplier = spawnIncludeScannerSupplier
        this.quoteIncludePaths =
            ImmutableList.builder<PathFragment?>()
                .addAll(quoteIncludePaths)
                .addAll(includePaths)
                .build()
        this.quoteIncludePathsFrameworkIndex = quoteIncludePaths.size()
        this.includePaths = ImmutableList.copyOf<PathFragment?>(includePaths)
        this.frameworkIncludePaths = ImmutableList.copyOf<PathFragment?>(frameworkIncludePaths)
        this.inclusionCache = InclusionCache()
        this.execRoot = execRoot
        this.outputPathFragment = outputPath.relativeTo(execRoot)
        this.absoluteRoot = Root.absoluteRoot(execRoot.getFileSystem())
    }

    /**
     * Implements a potentially parallel traversal over source files using a thread pool shared across
     * different IncludeScanner instances.
     */
    private inner class IncludeVisitor(
        actionExecutionMetadata: ActionExecutionMetadata?,
        actionExecutionContext: ActionExecutionContext,
        grepIncludes: Artifact?,
        grepIncludesExecutionPlatform: PlatformInfo?,
        headerData: IncludeScanningHeaderData
    ) : AbstractQueueVisitor(
        includePool,
        ExecutorOwnership.SHARED,
        ExceptionHandlingMode.FAIL_FAST,
        INCLUDE_SCANNING_ERROR_CLASSIFIER
    ) {
        private val actionExecutionMetadata: ActionExecutionMetadata?
        private val actionExecutionContext: ActionExecutionContext
        private val grepIncludes: Artifact?
        private val grepIncludesExecutionPlatform: PlatformInfo?
        private val headerData: IncludeScanningHeaderData

        /** The set of all processed inclusions, to avoid processing duplicate inclusions.  */
        private val visitedInclusions: MutableSet<ArtifactWithInclusionContext?> =
            Sets.newConcurrentHashSet<ArtifactWithInclusionContext?>()

        init {
            this.actionExecutionMetadata = actionExecutionMetadata
            this.actionExecutionContext = actionExecutionContext
            this.grepIncludes = grepIncludes
            this.grepIncludesExecutionPlatform = grepIncludesExecutionPlatform
            this.headerData = headerData
        }

        @Throws(InterruptedException::class, IOException::class, ExecException::class)
        fun processInternal(
            mainSource: Artifact?,
            sources: MutableCollection<Artifact>,
            cmdlineIncludes: MutableList<String?>,
            includes: MutableSet<Artifact>,
            pathHints: ImmutableSet<Artifact>
        ) {
            try {
                // Process cmd line includes, if specified.
                if (mainSource != null && !cmdlineIncludes.isEmpty()) {
                    processCmdlineIncludes(mainSource, cmdlineIncludes, includes)
                    sync()
                }

                processBulkAsync(sources, includes)
                sync()

                // Process include hints
                // TODO(ulfjack): Make this code go away. Use the new hinted inclusions instead.
                val hints: Hints? = parser.getHints()
                if (hints != null) {
                    // Follow "path" hints.
                    processBulkAsync(pathHints, includes)
                    // Follow "file" hints for the primary sources.
                    for (source in sources) {
                        processFileLevelHintsAsync(hints, source, includes)
                    }
                    sync()

                    // Follow "file" hints for all included headers, transitively.
                    var frontier: MutableSet<Artifact> = includes
                    while (!frontier.isEmpty()) {
                        val adjacent: MutableSet<Artifact> = Sets.newConcurrentHashSet<Artifact>()
                        for (include in frontier) {
                            processFileLevelHintsAsync(hints, include, adjacent)
                        }
                        sync()
                        // Keep novel nodes as the next frontier.
                        val iter: MutableIterator<Artifact> = adjacent.iterator()
                        while (iter.hasNext()) {
                            if (!includes.add(iter.next())) {
                                iter.remove()
                            }
                        }
                        frontier = adjacent
                    }
                }
            } catch (e: IOException) {
                // Careful: Do not leak visitation threads if we have an exception in the initial thread.
                sync()
                throw e
            } catch (e: InterruptedException) {
                sync()
                throw e
            } catch (e: ExecException) {
                sync()
                throw e
            }
        }

        /** Block for the completion of all outstanding visitations.  */
        @Throws(IOException::class, ExecException::class, InterruptedException::class)
        fun sync() {
            try {
                super.awaitQuiescence(true)
            } catch (e: InterruptedException) {
                throw InterruptedException("Interrupted during include visitation")
            } catch (e: UncheckedIOException) {
                throw e.getCause()
            } catch (e: ExecRuntimeException) {
                throw e.realCause
            } catch (e: InterruptedRuntimeException) {
                throw e.realCause
            }
        }

        /**
         * Processes a given file for includes and populates the provided set with the visited includes.
         * 
         * @param source the file to process
         * @param contextPathPos the position on the include path where the containing file was found,
         * or `-1` for top-level inclusions
         * @param contextKind the kind how the containing file was included, or null for top-level
         * inclusions
         * @param visited the set to receive the files that are transitively included by `source`
         */
        @Throws(IOException::class, ExecException::class, InterruptedException::class)
        fun process(
            source: Artifact,
            contextPathPos: Int,
            contextKind: IncludeParser.Inclusion.Kind?,
            visited: MutableSet<Artifact>
        ) {
            checkForInterrupt("processing", source)

            var inclusions: MutableCollection<Inclusion>? = null
            while (inclusions == null) {
                val future: SettableFuture<MutableCollection<Inclusion?>?> =
                    SettableFuture.create<MutableCollection<Inclusion?>?>()
                var previous: Future<MutableCollection<Inclusion?>?>? = fileParseCache.putIfAbsent(source, future)
                if (previous == null) {
                    previous = future
                    try {
                        future.set(
                            parser.extractInclusions(
                                source,
                                actionExecutionMetadata,
                                actionExecutionContext,
                                grepIncludes,
                                grepIncludesExecutionPlatform,
                                spawnIncludeScannerSupplier.get(),
                                isOutputFile(source.getExecPath())
                            )
                        )
                    } catch (t: Throwable) {
                        fileParseCache.remove(source)
                        future.setException(t)
                        throw t
                    }
                }
                try {
                    inclusions = Preconditions.checkNotNull<MutableCollection<Inclusion>?>(previous.get(), source)
                } catch (e: ExecutionException) {
                    // An exception occured when some other thread tried to load the same file that we are
                    // waiting for. If this is a MissingDepExecException, we have to simply retry as otherwise
                    // we'd end up in an unexpected state (not requesting any deps, but claiming that there
                    // are missing ones). For other exceptions, this might not be necessary but is safe to do
                    // and reduces complexity.
                }
            }

            val maybeShuffledInclusions: MutableCollection<Inclusion>?
            if (shouldShuffle) {
                // Shuffle the inclusions to get better parallelism. See b/62200470.
                val shuffledInclusions: MutableList<Inclusion> = ArrayList<Inclusion>(inclusions)
                Collections.shuffle(shuffledInclusions, CONSTANT_SEED_RANDOM)
                maybeShuffledInclusions = shuffledInclusions
            } else {
                maybeShuffledInclusions = inclusions
            }

            // For each inclusion: get or locate its target file & recursively process
            val helper =
                IncludeScannerHelper(includePaths, quoteIncludePaths, source)
            val parent: PathFragment = source.getExecPath().getParentDirectory()
            for (inclusion in maybeShuffledInclusions) {
                findAndProcess(
                    helper.createInclusionWithContext(inclusion, contextPathPos, contextKind),
                    source,
                    parent,
                    visited
                )
            }
        }

        /**
         * Same as [.process], but executes asynchronously if the #include lines of `source`
         * haven't been extracted yet. For sources that have already been extracted, just continue
         * walking them in the current thread. The overhead of scheduling this on other threads is
         * larger than the gain in concurrency. The only really slow operation is the (possibly remote)
         * extraction of includes.
         */
        @Throws(IOException::class, ExecException::class, InterruptedException::class)
        fun processAsyncIfNotExtracted(
            source: Artifact,
            contextPathPos: Int,
            contextKind: IncludeParser.Inclusion.Kind?,
            visited: MutableSet<Artifact>
        ) {
            val cacheResult: ListenableFuture<MutableCollection<Inclusion?>?>? = fileParseCache.get(source)
            if (cacheResult != null && cacheResult.isDone()) {
                process(source, contextPathPos, contextKind, visited)
            } else {
                super.execute(
                    Runnable {
                        try {
                            actionExecutionContext.getThreadStateReceiverForMetrics().started().use { ignored ->
                                process(source, contextPathPos, contextKind, visited)
                            }
                        } catch (e: IOException) {
                            throw UncheckedIOException(e)
                        } catch (e: ExecException) {
                            throw ExecRuntimeException(e)
                        } catch (e: InterruptedException) {
                            throw InterruptedRuntimeException(e)
                        }
                    })
            }
        }

        /** Visits an inclusion starting from a source file.  */
        @Throws(IOException::class, ExecException::class, InterruptedException::class)
        fun findAndProcess(
            inclusion: InclusionWithContext, source: Artifact, parent: PathFragment, visited: MutableSet<Artifact>
        ) {
            // Try to find the included file relative to the file that contains the inclusion. Relative
            // inclusions are handled like the first entry on the quote include path
            var includeFile: Artifact? = locateRelative(inclusion.getInclusion(), headerData, source, parent)
            var contextPathPos = 0
            var contextKind: IncludeParser.Inclusion.Kind? = null

            checkForInterrupt("visiting", source)

            // If nothing has been found, get an inclusion from the cache. This will automatically search
            // on the include paths and populate the cache if necessary.
            if (includeFile == null) {
                val result = inclusionCache.lookup(inclusion, headerData)
                includeFile = result.path
                contextPathPos = result.includePosition
                contextKind = inclusion.getContextKind()
            }

            // Recursively process the found file (if not yet done).
            if (includeFile != null && !isIllegalOutputFile(
                    includeFile.getExecPath(),
                    headerData
                ) && headerData.isLegalHeader(includeFile)
                && visitedInclusions.add(
                    ArtifactWithInclusionContext(includeFile, contextKind, contextPathPos)
                )
            ) {
                visited.add(includeFile)
                if (headerData.isModularHeader(includeFile)) {
                    return
                }
                processAsyncIfNotExtracted(includeFile, contextPathPos, contextKind, visited)
            }
        }

        /**
         * Processes a given list of includes for a given base file and populates the provided set with
         * the visited includes
         * 
         * @param source the source file used as a reference for finding includes
         * @param includes the list of -include option strings to locate and process
         * @param visited the set of files that are transitively included by `includes` to
         * populate
         */
        @Throws(IOException::class, ExecException::class, InterruptedException::class)
        fun processCmdlineIncludes(
            source: Artifact, includes: MutableList<String?>, visited: MutableSet<Artifact>
        ) {
            val parent: PathFragment = source.getExecPath().getParentDirectory()
            for (incl in includes) {
                val inclusion = InclusionWithContext(incl, IncludeParser.Inclusion.Kind.QUOTE)
                findAndProcess(inclusion, source, parent, visited)
            }
        }

        /**
         * Processes a bunch sources asynchronously and adds them and their included files to the
         * provided set.
         * 
         * @param sources the files to process and add to the set
         * @param visited the set to receive the files that are transitively included by `sources`
         */
        @Throws(IOException::class, ExecException::class, InterruptedException::class)
        fun processBulkAsync(sources: MutableCollection<Artifact>, visited: MutableSet<Artifact>) {
            for (source in sources) {
                // TODO(djasper): This looks suspicious. We should only stop based on visitedInclusions.
                if (!visited.add(source)) {
                    continue
                }

                processAsyncIfNotExtracted(source,  /*contextPathPos=*/-1,  /*contextKind=*/null, visited)
            }
        }

        fun processFileLevelHintsAsync(
            hints: Hints, include: Artifact, alsoVisited: MutableSet<Artifact>
        ) {
            val sources: MutableCollection<Artifact> = hints.getFileLevelHintedInclusionsLegacy(include)
            // Early-out if there's nothing to do to avoid enqueuing a closure
            if (sources.isEmpty()) {
                return
            }
            super.execute(
                Runnable {
                    try {
                        actionExecutionContext.getThreadStateReceiverForMetrics().started().use { ignored ->
                            processBulkAsync(sources, alsoVisited)
                        }
                    } catch (e: IOException) {
                        throw UncheckedIOException(e)
                    } catch (e: ExecException) {
                        throw ExecRuntimeException(e)
                    } catch (e: InterruptedException) {
                        throw InterruptedRuntimeException(e)
                    }
                })
        }
    }

    private class ExecRuntimeException(e: ExecException?) : RuntimeException(e) {
        private val cause: ExecException?

        init {
            this.cause = e
        }

        val realCause: ExecException?
            get() = cause
    }

    private class InterruptedRuntimeException(val realCause: InterruptedException?) : RuntimeException(
        realCause
    )

    companion object {
        // We are using this Random just for shuffling, so keep the order deterministic by hardcoding
        // the seed.
        private val CONSTANT_SEED_RANDOM: Random = Random(88)

        @Throws(InterruptedException::class)
        private fun checkForInterrupt(operation: String?, source: Any?) {
            // We require passing in the operation and the source Path / Artifact to avoid intermediate
            // String operations. The include scanner is performance critical and this showed up in a
            // profiler.
            if (Thread.interrupted()) {
                throw InterruptedException(
                    "Include scanning interrupted while " + operation + " " + source
                )
            }
        }

        /**
         * Treats [LostInputsExecException] with higher priority so that rewinding can be initiated.
         * 
         * 
         * Notably, this allows lost inputs to be prioritized over [MissingDepExecException],
         * which is more likely to resolve on its own (Skyframe evaluation of the missing dep completes).
         */
        private val INCLUDE_SCANNING_ERROR_CLASSIFIER: ErrorClassifier = object : ErrorClassifier() {
            override fun classifyException(e: Exception): ErrorClassification {
                return when (e) {
                    -> if (e.getCause() is LostInputsExecException)
                        ErrorClassification.NOT_CRITICAL_HIGHER_PRIORITY
                    else
                        ErrorClassification.NOT_CRITICAL

                    -> ErrorClassification.NOT_CRITICAL
                    -> ErrorClassification.NOT_CRITICAL
                    -> ErrorClassification.CRITICAL
                    else -> ErrorClassification.NOT_CRITICAL
                }
            }
        }
    }
}
