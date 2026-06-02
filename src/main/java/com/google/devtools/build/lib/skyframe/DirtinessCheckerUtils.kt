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

import com.google.devtools.build.lib.skyframe.SkyFunctions.DIRECTORY_LISTING_STATE

/** Utilities for checking dirtiness of keys (mainly filesystem keys) in the graph.  */
object DirtinessCheckerUtils {
    @kotlin.jvm.JvmStatic
    fun createBasicFilesystemDirtinessChecker(): UnionDirtinessChecker {
        return UnionDirtinessChecker(
            com.google.common.collect.ImmutableList.of<E?>(FileDirtinessChecker(), DirectoryDirtinessChecker())
        )
    }

    /** Checks dirtiness of file keys in the graph.  */
    class FileDirtinessChecker : SkyValueDirtinessChecker() {
        public override fun applies(skyKey: SkyKey): Boolean {
            return skyKey.functionName() == FileStateKey.FILE_STATE
        }

        @Throws(IOException::class)
        public override fun createNewValue(
            key: SkyKey, syscallCache: SyscallCache?, tsgm: TimestampGranularityMonitor?
        ): SkyValue? {
            return FileStateValue.create(key.argument() as RootedPath?, syscallCache, tsgm)
        }
    }

    /** Checks dirtiness of directory keys in the graph.  */
    class DirectoryDirtinessChecker : SkyValueDirtinessChecker() {
        public override fun applies(skyKey: SkyKey): Boolean {
            return skyKey.functionName() == DIRECTORY_LISTING_STATE
        }

        @Throws(IOException::class)
        public override fun createNewValue(
            key: SkyKey, syscallCache: SyscallCache, tsgm: TimestampGranularityMonitor?
        ): SkyValue {
            val rootedPath: RootedPath = key.argument() as RootedPath
            return DirectoryListingStateValue.create(syscallCache.readdir(rootedPath.asPath()))
        }
    }

    internal class MissingDiffDirtinessChecker(missingDiffPackageRoots: MutableSet<Root?>) :
        SkyValueDirtinessChecker() {
        private val missingDiffPackageRoots: MutableSet<Root?>
        private val checker = createBasicFilesystemDirtinessChecker()

        init {
            this.missingDiffPackageRoots = missingDiffPackageRoots
        }

        public override fun applies(key: SkyKey): Boolean {
            return checker.applies(key)
                    && missingDiffPackageRoots.contains((key.argument() as RootedPath).getRoot())
        }

        @Throws(IOException::class)
        public override fun createNewValue(
            key: SkyKey?, syscallCache: SyscallCache?, tsgm: TimestampGranularityMonitor?
        ): SkyValue {
            return checker.createNewValue(key, syscallCache, tsgm)
        }
    }

    /**
     * Serves for tracking whether there are external and output files {@see ExternalFilesKnowledge}.
     * Filtering of files, for which the new values should not be injected into evaluator, is done in
     * SequencedSkyframeExecutor.handleChangedFiles().
     */
    internal class ExternalDirtinessChecker(
        externalFilesHelper: ExternalFilesHelper,
        fileTypesToCheck: EnumSet<com.google.devtools.build.lib.skyframe.ExternalFilesHelper.FileType?>
    ) : SkyValueDirtinessChecker() {
        private val externalFilesHelper: ExternalFilesHelper
        private val fileTypesToCheck: EnumSet<com.google.devtools.build.lib.skyframe.ExternalFilesHelper.FileType?>

        private val checker = createBasicFilesystemDirtinessChecker()
        private val dirtyExternalRepos: ConcurrentHashMap<RepositoryName?, RootedPath?> =
            ConcurrentHashMap<RepositoryName?, RootedPath?>()

        init {
            this.externalFilesHelper = externalFilesHelper
            this.fileTypesToCheck = fileTypesToCheck
        }

        public override fun applies(key: SkyKey): Boolean {
            if (!checker.applies(key)) {
                return false
            }
            val fileType: com.google.devtools.build.lib.skyframe.ExternalFilesHelper.FileType =
                externalFilesHelper.getAndNoteFileType(key.argument() as RootedPath?)
            return fileTypesToCheck.contains(fileType)
        }

        public override fun createNewValue(
            key: SkyKey?, syscallCache: SyscallCache?, tsgm: TimestampGranularityMonitor?
        ): SkyValue? {
            throw java.lang.UnsupportedOperationException()
        }

        @Throws(IOException::class)
        public override fun check(
            skyKey: SkyKey,
            oldValue: SkyValue?,
            oldMtsv: com.google.devtools.build.skyframe.Version?,
            syscallCache: SyscallCache?,
            tsgm: TimestampGranularityMonitor?
        ): SkyValueDirtinessChecker.DirtyResult {
            val rootedPath: RootedPath = skyKey.argument() as RootedPath
            val fileType: com.google.devtools.build.lib.skyframe.ExternalFilesHelper.FileType =
                externalFilesHelper.getAndNoteFileType(rootedPath)
            val cacheable = isCacheableType(fileType)
            val newValue: SkyValue =
                checker.createNewValue(skyKey, if (cacheable) syscallCache else SyscallCache.NO_CACHE, tsgm)
            if (com.google.common.base.Objects.equal(newValue, oldValue)) {
                return SkyValueDirtinessChecker.DirtyResult.notDirty()
            }
            if (cacheable) {
                return SkyValueDirtinessChecker.DirtyResult.dirtyWithNewValue(newValue)
            }
            if (fileType == com.google.devtools.build.lib.skyframe.ExternalFilesHelper.FileType.EXTERNAL_REPO) {
                val repositoryName: RepositoryName? = externalFilesHelper.getExternalRepoName(rootedPath)
                if (repositoryName != null) {
                    dirtyExternalRepos.putIfAbsent(repositoryName, rootedPath)
                }
            }
            // Files under output_base/external have a dependency on the WORKSPACE file, so we don't add
            // a new SkyValue to the graph yet because it might change once the WORKSPACE file has been
            // parsed. Similarly, output files might change during execution.
            return SkyValueDirtinessChecker.DirtyResult.dirty()
        }

        fun getDirtyExternalRepos(): MutableMap<RepositoryName?, RootedPath?> {
            return Collections.unmodifiableMap<RepositoryName?, RootedPath?>(dirtyExternalRepos)
        }

        companion object {
            private fun isCacheableType(fileType: com.google.devtools.build.lib.skyframe.ExternalFilesHelper.FileType): Boolean {
                return when (fileType) {
                    com.google.devtools.build.lib.skyframe.ExternalFilesHelper.FileType.INTERNAL, com.google.devtools.build.lib.skyframe.ExternalFilesHelper.FileType.EXTERNAL_OTHER, com.google.devtools.build.lib.skyframe.ExternalFilesHelper.FileType.BUNDLED -> true
                    com.google.devtools.build.lib.skyframe.ExternalFilesHelper.FileType.EXTERNAL_REPO, com.google.devtools.build.lib.skyframe.ExternalFilesHelper.FileType.OUTPUT -> false
                    com.google.devtools.build.lib.skyframe.ExternalFilesHelper.FileType.REPO_CONTENTS_CACHE_DIRS -> throw java.lang.IllegalStateException(
                        "Repo contents cache dirs are not expected to be checked for dirtiness"
                    )
                }
            }
        }
    }

    /** [SkyValueDirtinessChecker] that encompasses a union of other dirtiness checkers.  */
    class UnionDirtinessChecker(dirtinessCheckers: Iterable<SkyValueDirtinessChecker>) : SkyValueDirtinessChecker() {
        private val dirtinessCheckers: Iterable<SkyValueDirtinessChecker>

        init {
            this.dirtinessCheckers = dirtinessCheckers
        }

        private fun getChecker(key: SkyKey?): SkyValueDirtinessChecker? {
            for (dirtinessChecker in dirtinessCheckers) {
                if (dirtinessChecker.applies(key)) {
                    return dirtinessChecker
                }
            }
            return null
        }

        public override fun applies(key: SkyKey?): Boolean {
            return getChecker(key) != null
        }

        @Throws(IOException::class)
        public override fun createNewValue(
            key: SkyKey?, syscallCache: SyscallCache?, tsgm: TimestampGranularityMonitor?
        ): SkyValue {
            return com.google.common.base.Preconditions.checkNotNull<Any?>(getChecker(key), key)
                .createNewValue(key, syscallCache, tsgm)
        }

        @Throws(IOException::class)
        public override fun check(
            key: SkyKey?,
            oldValue: SkyValue?,
            oldMtsv: com.google.devtools.build.skyframe.Version?,
            syscallCache: SyscallCache?,
            tsgm: TimestampGranularityMonitor?
        ): DirtyResult {
            return com.google.common.base.Preconditions.checkNotNull<Any?>(getChecker(key), key)
                .check(key, oldValue, oldMtsv, syscallCache, tsgm)
        }

        @Throws(IOException::class)
        public override fun getMaxTransitiveSourceVersionForNewValue(
            key: SkyKey?,
            value: SkyValue?
        ): com.google.devtools.build.skyframe.Version? {
            return com.google.common.base.Preconditions.checkNotNull<Any?>(getChecker(key), key)
                .getMaxTransitiveSourceVersionForNewValue(key, value)
        }
    }
}
