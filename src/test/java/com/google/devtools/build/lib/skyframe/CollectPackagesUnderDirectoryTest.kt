// Copyright 2019 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.bazel.bzlmod.ModuleFileFunction

/** Test for [CollectPackagesUnderDirectoryFunction].  */
@RunWith(JUnit4::class)
class CollectPackagesUnderDirectoryTest

    : AbstractCollectPackagesUnderDirectoryTest() {
    protected val workspacePathString: String
        get() = "/workspace"

    protected val buildFileNamesByPriority: MutableList<BuildFileName>
        get() = BazelSkyframeExecutorConstants.BUILD_FILES_BY_PRIORITY

    protected val extraSkyFunctions: com.google.common.collect.ImmutableMap<SkyFunctionName?, SkyFunction?>
        get() = com.google.common.collect.ImmutableMap.of<SkyFunctionName?, SkyFunction?>(
            SkyFunctions.MODULE_FILE,
            ModuleFileFunction(
                ruleClassProvider.getBazelStarlarkEnvironment(),
                directories.getWorkspace(),
                com.google.common.collect.ImmutableMap.of<K?, V?>()
            )
        )

    protected override fun makeSkyframeExecutorFactory(): SkyframeExecutorFactory {
        return SequencedSkyframeExecutorFactory()
    }

    protected override fun useVirtualSourceRoot(): Boolean {
        return false
    }
}
