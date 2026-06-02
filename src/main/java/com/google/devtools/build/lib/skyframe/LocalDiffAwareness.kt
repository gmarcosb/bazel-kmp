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


import com.google.devtools.build.lib.cmdline.IgnoredSubdirectories

/**
 * File system watcher for local filesystems. It's able to provide a list of changed files between
 * two consecutive calls. On Linux, uses the standard Java WatchService, which uses 'inotify' and,
 * on OS X, uses [MacOSXFsEventsDiffAwareness], which use FSEvents.
 * 
 * 
 * 
 * This is an abstract class, specialized by [MacOSXFsEventsDiffAwareness] and
 * [WatchServiceDiffAwareness].
 */
abstract class LocalDiffAwareness protected constructor(watchRoot: java.nio.file.Path) : DiffAwareness {
    /** Option to enable / disable local diff awareness.  */
    @com.google.devtools.common.options.OptionsClass
    abstract class Options : com.google.devtools.common.options.OptionsBase() {
        @kotlin.jvm.JvmField
        @get:com.google.devtools.common.options.Option(
            name = "watchfs",
            defaultValue = "false",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.UNKNOWN],
            help = ("On Linux/macOS: If true, %{product} tries to use the operating system's file watch "
                    + "service for local changes instead of scanning every file for a change. On "
                    + "Windows: this flag currently is a non-op but can be enabled in conjunction "
                    + "with --experimental_windows_watchfs. On any OS: The behavior is undefined "
                    + "if your workspace is on a network file system, and files are edited on a "
                    + "remote machine.")
        )
        abstract var watchFS: Boolean

        @get:com.google.devtools.common.options.Option(
            name = "experimental_windows_watchfs",
            defaultValue = "false",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.UNKNOWN],
            help = ("If true, experimental Windows support for --watchfs is enabled. Otherwise --watchfs"
                    + "is a non-op on Windows. Make sure to also enable --watchfs.")
        )
        abstract val windowsWatchFS: Boolean
    }

    /** Factory for creating [LocalDiffAwareness] instances.  */
    class Factory(
        excludedNetworkFileSystemsPrefixes: com.google.common.collect.ImmutableList<String?>,
        fsEventsNativeDepsService: FsEventsNativeDepsService?
    ) : DiffAwareness.Factory {
        private val excludedNetworkFileSystemsPrefixes: com.google.common.collect.ImmutableList<String?>
        private val fsEventsNativeDepsService: FsEventsNativeDepsService?

        /**
         * Creates a new factory; the file system watcher may not work on all file systems, particularly
         * for network file systems. The prefix list can be used to exclude known paths that point to
         * network file systems.
         */
        init {
            this.excludedNetworkFileSystemsPrefixes = excludedNetworkFileSystemsPrefixes
            this.fsEventsNativeDepsService = fsEventsNativeDepsService
        }

        public override fun maybeCreate(
            pathEntry: Root,
            ignoredPaths: IgnoredSubdirectories?,
            optionsProvider: com.google.devtools.common.options.OptionsProvider?
        ): DiffAwareness? {
            val resolvedPathEntry: com.google.devtools.build.lib.vfs.Path
            try {
                resolvedPathEntry = pathEntry.asPath().resolveSymbolicLinks()
            } catch (e: IOException) {
                return null
            }
            val resolvedPathEntryFragment: PathFragment = resolvedPathEntry.asFragment()
            // There's no good way to automatically detect network file systems. We rely on a list of
            // paths to exclude for now (and maybe add a command-line option in the future?).
            for (prefix in excludedNetworkFileSystemsPrefixes) {
                if (resolvedPathEntryFragment.startsWith(PathFragment.create(prefix))) {
                    return null
                }
            }
            val watchRoot: java.nio.file.Path? =
                java.nio.file.Path.of(StringEncoding.internalToPlatform(resolvedPathEntryFragment.getPathString()))
            // On OSX uses FsEvents due to https://bugs.openjdk.java.net/browse/JDK-7133447
            if (com.google.devtools.build.lib.util.OS.getCurrent() == com.google.devtools.build.lib.util.OS.DARWIN) {
                return MacOSXFsEventsDiffAwareness(watchRoot, ignoredPaths, fsEventsNativeDepsService)
            }

            return WatchServiceDiffAwareness(watchRoot, ignoredPaths)
        }
    }

    private var numGetCurrentViewCalls = 0

    /** Root directory to watch. This is an absolute path.  */
    protected val watchRoot: java.nio.file.Path

    init {
        this.watchRoot = watchRoot
    }

    /**
     * The WatchService is inherently sequential and side-effectful, so we enforce this by only
     * supporting [.getDiff] calls that happen to be sequential.
     */
    @com.google.common.annotations.VisibleForTesting
    internal class SequentialView(
        private val owner: LocalDiffAwareness?,
        private val position: Int,
        modifiedAbsolutePaths: MutableSet<java.nio.file.Path>
    ) : DiffAwareness.View {
        private val modifiedAbsolutePaths: MutableSet<java.nio.file.Path>

        init {
            this.modifiedAbsolutePaths = modifiedAbsolutePaths
        }

        override fun toString(): String {
            return String.format(
                "SequentialView[owner=%s, position=%d, modifiedAbsolutePaths=%s]", owner,
                position, modifiedAbsolutePaths
            )
        }
    }

    protected val isFirstCall: Boolean
        /**
         * Returns true on any call before first call to [.newView].
         */
        get() = numGetCurrentViewCalls == 0

    /**
     * Create a new views using a list of modified absolute paths. This will increase the view
     * counter.
     */
    protected fun newView(modifiedAbsolutePaths: MutableSet<java.nio.file.Path>): SequentialView {
        numGetCurrentViewCalls++
        return SequentialView(this, numGetCurrentViewCalls, modifiedAbsolutePaths)
    }

    @Throws(IncompatibleViewException::class, BrokenDiffAwarenessException::class)
    public override fun getDiff(oldView: View?, newView: View?): ModifiedFileSet {
        if (oldView == null) {
            return ModifiedFileSet.EVERYTHING_MODIFIED
        }

        val oldSequentialView: SequentialView
        val newSequentialView: SequentialView
        try {
            oldSequentialView = oldView as SequentialView
            newSequentialView = newView as SequentialView
        } catch (e: java.lang.ClassCastException) {
            throw IncompatibleViewException("Given views are not from LocalDiffAwareness")
        }
        if (!areInSequence(oldSequentialView, newSequentialView)) {
            return ModifiedFileSet.EVERYTHING_MODIFIED
        }

        val resultBuilder: ModifiedFileSet.Builder = ModifiedFileSet.builder()
        for (modifiedPath in newSequentialView.modifiedAbsolutePaths) {
            if (!modifiedPath.startsWith(watchRoot)) {
                throw BrokenDiffAwarenessException(
                    String.format("%s is not under %s", modifiedPath, watchRoot)
                )
            }
            val relativePath: PathFragment =
                PathFragment.create(
                    StringEncoding.platformToInternal(watchRoot.relativize(modifiedPath).toString())
                )
            if (!relativePath.isEmpty()) {
                resultBuilder.modify(relativePath)
            }
        }
        return resultBuilder.build()
    }

    public override fun name(): String {
        return "local"
    }

    companion object {
        /**
         * A view that results in any subsequent getDiff calls returning
         * [ModifiedFileSet.EVERYTHING_MODIFIED]. Use this if --watchFs is disabled.
         * 
         * 
         * The position is set to -2 in order for [.areInSequence] below to always return false
         * if this view is passed to it. Any negative number would work; we don't use -1 as the other
         * view may have a position of 0.
         */
        @kotlin.jvm.JvmField
        val EVERYTHING_MODIFIED: View = SequentialView( /*owner=*/null,  /*position=*/
            -2,
            com.google.common.collect.ImmutableSet.of<java.nio.file.Path?>()
        )

        @kotlin.jvm.JvmStatic
        fun areInSequence(oldView: SequentialView, newView: SequentialView): Boolean {
            // Keep this in sync with the EVERYTHING_MODIFIED View above.
            return oldView.owner === newView.owner && (oldView.position + 1) == newView.position
        }
    }
}
