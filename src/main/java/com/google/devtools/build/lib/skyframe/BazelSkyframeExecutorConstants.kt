// Copyright 2017 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.packages.BuildFileName

/** Hardcoded constants describing bazel-on-skyframe behavior.  */
object BazelSkyframeExecutorConstants {
    val CROSS_REPOSITORY_LABEL_VIOLATION_STRATEGY: CrossRepositoryLabelViolationStrategy? =
        CrossRepositoryLabelViolationStrategy.ERROR

    @kotlin.jvm.JvmField
    val BUILD_FILES_BY_PRIORITY: com.google.common.collect.ImmutableList<BuildFileName?> =
        com.google.common.collect.ImmutableList.of<BuildFileName?>(BuildFileName.BUILD_DOT_BAZEL, BuildFileName.BUILD)

    val ACTION_ON_IO_EXCEPTION_READING_BUILD_FILE: ActionOnIOExceptionReadingBuildFile =
        UseOriginalIOException.Companion.INSTANCE

    val ACTION_ON_FILESYSTEM_ERROR_CODE_LOADING_BZL_FILE: ActionOnFilesystemErrorCodeLoadingBzlFile =
        ActionOnFilesystemErrorCodeLoadingBzlFile { filesystemCode: Filesystem.Code? -> filesystemCode === FailureDetails.Filesystem.Code.REMOTE_FILE_EVICTED }

    const val USE_REPO_DOT_BAZEL: Boolean = true

    val DIFF_CHECK_NOTIFICATION_OPTIONS: DiffCheckNotificationOptions = object : DiffCheckNotificationOptions {
        override fun allowDiffCheck(
            versionDiff: EvaluatingVersionDiff?,
            eventHandler: com.google.devtools.build.lib.events.EventHandler?,
            options: com.google.devtools.common.options.OptionsProvider?
        ): Boolean {
            return true
        }

        val statusMessage: String
            get() = "Checking for file changes..."

        val statusUpdateDelay: java.time.Duration?
            get() = java.time.Duration.ofSeconds(1)
    }

    @kotlin.jvm.JvmStatic
    fun newBazelSkyframeExecutorBuilder(): com.google.devtools.build.lib.skyframe.SequencedSkyframeExecutor.Builder {
        return SequencedSkyframeExecutor.Companion.builder()
            .setIgnoredSubdirectories(IgnoredSubdirectoriesFunction.INSTANCE)
            .setActionOnIOExceptionReadingBuildFile(ACTION_ON_IO_EXCEPTION_READING_BUILD_FILE)
            .setActionOnFilesystemErrorCodeLoadingBzlFile(
                ACTION_ON_FILESYSTEM_ERROR_CODE_LOADING_BZL_FILE
            )
            .setShouldUseRepoDotBazel(USE_REPO_DOT_BAZEL)
            .setCrossRepositoryLabelViolationStrategy(CROSS_REPOSITORY_LABEL_VIOLATION_STRATEGY)
            .setBuildFilesByPriority(BUILD_FILES_BY_PRIORITY)
            .setDiffCheckNotificationOptions(DIFF_CHECK_NOTIFICATION_OPTIONS)
    }
}
