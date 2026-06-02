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
package com.google.devtools.build.lib.packages.producers

import com.google.common.base.Preconditions
import com.google.common.collect.Lists
import com.google.devtools.build.lib.actions.FileValue
import com.google.devtools.build.lib.skyframe.FileKey
import com.google.devtools.build.lib.util.Pair
import com.google.devtools.build.lib.vfs.Dirent
import java.util.function.Consumer
import kotlin.collections.ArrayList
import kotlin.collections.MutableSet

/**
 * [PatternWithWildcardProducer] is a sub-[StateMachine] created by [ ]. It handles glob pattern fragment which contains wildcard characters (`*`
 * or `**`).
 * 
 * 
 * Since wildcard is present, all dirents can be a possible pattern fragment match. So we need to
 * query the [DirectoryListingValue] and match all [Dirent]s to the glob pattern
 * fragment.
 * 
 * 
 * Handling symlink dirents requires special consideration. We query [FileValue]s for all
 * symlink dirents in a batch. The results are put in the [.symlinks] container. The [ ][.processSymlinks] method is invoked only once to handle all symlinks.
 * 
 * 
 * All matching dirents are handled by creating the [DirectoryDirentProducer]s for each one
 * of them.
 */
internal class PatternWithWildcardProducer
    (
    globDetail: GlobDetail,
    base: PathFragment,
    fragmentIndex: Int,
    resultSink: FragmentProducer.ResultSink,
    visitedGlobSubTasks: MutableSet<Pair<PathFragment?, Int?>?>?
) : StateMachine, Consumer<SkyValue?>, SymlinkProducer.ResultSink {
    // -------------------- Input --------------------
    private val globDetail: GlobDetail

    /** The [PathFragment] of the directory prefixed by the package fragments.  */
    private val base: PathFragment

    private val fragmentIndex: Int

    // -------------------- Internal State --------------------
    private var directoryListingValue: DirectoryListingValue? = null

    /** Holds both symlink path and target path for all symlink type dirents.  */
    private var symlinks: ArrayList<Pair<FileKey?, FileValue?>>? = null

    private var symlinksCount = 0
    private val visitedGlobSubTasks: MutableSet<Pair<PathFragment?, Int?>?>?

    // -------------------- Output --------------------
    private val resultSink: FragmentProducer.ResultSink

    init {
        this.globDetail = globDetail
        this.base = base
        this.fragmentIndex = fragmentIndex
        this.resultSink = resultSink
        this.visitedGlobSubTasks = visitedGlobSubTasks
    }

    override fun step(tasks: StateMachine.Tasks): StateMachine {
        tasks.lookUp(
            DirectoryListingValue.key(RootedPath.toRootedPath(globDetail.packageRoot, base)),
            this as Consumer<SkyValue?>
        )
        return StateMachine { tasks: StateMachine.Tasks? -> this.processDirectoryListingValue(tasks) }
    }

    override fun accept(skyValue: SkyValue?) {
        directoryListingValue = skyValue as DirectoryListingValue?
    }

    private fun processDirectoryListingValue(tasks: StateMachine.Tasks): StateMachine {
        Preconditions.checkNotNull<DirectoryListingValue?>(directoryListingValue)
        val patternFragment = globDetail.patternFragments.get(fragmentIndex)
        for (dirent in directoryListingValue.getDirents()) {
            if (dirent.getType() == Dirent.Type.UNKNOWN) {
                continue
            }

            val direntName: String = dirent.getName()

            if (!UnixGlob.matches(patternFragment, direntName, globDetail.regexPatternCache)) {
                continue
            }

            // At this point, we know that the dirent matches current pattern fragment but we don't yet
            // know if it belongs in the result. Delay creating the full PathFragment until we actually
            // need it.
            if (dirent.getType() == Dirent.Type.SYMLINK) {
                tasks.enqueue(
                    SymlinkProducer(
                        FileValue.key(
                            RootedPath.toRootedPath(globDetail.packageRoot, base.getChild(direntName))
                        ),
                        this as SymlinkProducer.ResultSink
                    )
                )
                ++symlinksCount
            } else if (dirent.getType() == Dirent.Type.DIRECTORY) {
                tasks.enqueue(
                    DirectoryDirentProducer(
                        globDetail,
                        base.getChild(direntName),
                        fragmentIndex,
                        resultSink,
                        visitedGlobSubTasks
                    )
                )
            } else {
                if (FragmentProducer.Companion.shouldAddFileMatchingToResult(fragmentIndex, globDetail)) {
                    resultSink.acceptPathFragmentWithPackageFragment(base.getChild(direntName))
                }
            }
        }

        if (symlinksCount > 0) {
            // When there are multiple symlinks under the sub-directory, we want to put all symlink
            // `FileValue`s into a container and handle all of them in a single `processSymlinks`
            // execution.
            // At this point, we already knew number symlinks under the sub-directory, so allocate the
            // same size for the symlinks array in advance.
            symlinks = Lists.newArrayListWithCapacity<Pair<FileKey?, FileValue?>?>(symlinksCount)
            return StateMachine { tasks: StateMachine.Tasks? -> this.processSymlinks(tasks) }
        }
        return StateMachine.DONE
    }

    override fun acceptSymlinkFileValue(symlinkValue: FileValue?, symlinkKey: FileKey?) {
        symlinks!!.add(Pair.of<FileKey?, FileValue?>(symlinkKey, symlinkValue))
    }

    override fun acceptInconsistentFilesystemException(exception: InconsistentFilesystemException?) {
        resultSink.acceptGlobError(GlobError.Companion.of(exception))
    }

    private fun processSymlinks(tasks: StateMachine.Tasks): StateMachine {
        if (symlinks!!.isEmpty() || symlinks.size() < symlinksCount) {
            // It is possible that some symlinks cannot be accepted due to inconsistent filesystem error.
            // In this case, since the `InconsistentFilesystemException` is accepted and glob function
            // computation will error out, it is unnecessary to proceed.
            return StateMachine.DONE
        }

        for (symlink in symlinks!!) {
            val symlinkKey = symlink.first
            val symlinkValue: FileValue? = symlink.second

            if (!symlinkValue.exists()) {
                // Tolerate when the symlink is pointing to a non-existing path.
                continue
            }

            // This check is more strict than necessary: we raise an error if globbing traverses into
            // a directory for any reason, even though it's only necessary if that reason was the
            // resolution of a recursive glob ("**"). Fixing this would require plumbing the ancestor
            // symlink information through DirectoryListingValue.
            if (symlinkValue.isDirectory()
                && symlinkValue.unboundedAncestorSymlinkExpansionChain() != null
            ) {
                tasks.lookUp(
                    FileSymlinkInfiniteExpansionUniquenessFunction.Companion.key(
                        symlinkValue.unboundedAncestorSymlinkExpansionChain()
                    ),
                    Consumer { v: SkyValue? -> })
                resultSink.acceptGlobError(
                    GlobError.Companion.of(
                        FileSymlinkInfiniteExpansionException(
                            symlinkValue.pathToUnboundedAncestorSymlinkExpansionChain(),
                            symlinkValue.unboundedAncestorSymlinkExpansionChain()
                        )
                    )
                )
                return StateMachine.DONE
            }

            // Use the symlink path instead of the target path.
            val direntPath: PathFragment = symlinkKey!!.argument().getRootRelativePath()
            if (symlinkValue.isDirectory()) {
                tasks.enqueue(
                    DirectoryDirentProducer(
                        globDetail, direntPath, fragmentIndex, resultSink, visitedGlobSubTasks
                    )
                )
            } else {
                if (FragmentProducer.Companion.shouldAddFileMatchingToResult(fragmentIndex, globDetail)) {
                    resultSink.acceptPathFragmentWithPackageFragment(direntPath)
                }
            }
        }

        // After all symlinks of dirents are processed, `symlinks` array list is useless and should be
        // garbage collected.
        symlinks = null
        return StateMachine.DONE
    }
}
