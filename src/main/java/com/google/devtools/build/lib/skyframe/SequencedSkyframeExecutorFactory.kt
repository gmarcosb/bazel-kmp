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

import com.google.devtools.build.lib.actions.ActionKeyContext

/** A factory of SkyframeExecutors that returns SequencedSkyframeExecutor.  */
class SequencedSkyframeExecutorFactory : SkyframeExecutorFactory {
    override fun create(
        pkgFactory: PackageFactory?,
        fileSystem: com.google.devtools.build.lib.vfs.FileSystem?,
        directories: BlazeDirectories?,
        actionKeyContext: ActionKeyContext?,
        workspaceStatusActionFactory: Factory?,
        diffAwarenessFactories: Iterable<out DiffAwareness.Factory?>?,
        extraSkyFunctions: com.google.common.collect.ImmutableMap<SkyFunctionName?, SkyFunction?>?,
        syscallCache: SyscallCache?,
        allowExternalRepositories: Boolean,
        repoContentsCachePathSupplier: java.util.function.Supplier<com.google.devtools.build.lib.vfs.Path?>?,
        skyKeyStateReceiver: SkyKeyStateReceiver?,
        bugReporter: BugReporter?
    ): SkyframeExecutor {
        return BazelSkyframeExecutorConstants.newBazelSkyframeExecutorBuilder()
            .setPkgFactory(pkgFactory)
            .setFileSystem(fileSystem)
            .setDirectories(directories)
            .setActionKeyContext(actionKeyContext)
            .setWorkspaceStatusActionFactory(workspaceStatusActionFactory)
            .setDiffAwarenessFactories(diffAwarenessFactories)
            .setExtraSkyFunctions(extraSkyFunctions)
            .setSyscallCache(syscallCache)
            .allowExternalRepositories(allowExternalRepositories)
            .setRepoContentsCachePathSupplier(repoContentsCachePathSupplier)
            .setSkyKeyStateReceiver(skyKeyStateReceiver)
            .setBugReporter(bugReporter)
            .build()
    }
}
