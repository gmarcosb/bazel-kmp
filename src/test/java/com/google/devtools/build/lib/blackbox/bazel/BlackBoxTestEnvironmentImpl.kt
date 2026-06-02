// Copyright 2018 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.blackbox.bazel

import com.google.devtools.build.lib.bazel.repository.decompressor.DecompressorDescriptor.Builder.build
import com.google.devtools.build.lib.blackbox.bazel.DefaultToolsSetup
import com.google.devtools.build.lib.blackbox.bazel.RunfilesUtil
import com.google.devtools.build.lib.blackbox.framework.BlackBoxTestContext
import com.google.devtools.build.lib.blackbox.framework.BlackBoxTestEnvironment
import com.google.devtools.build.lib.blackbox.framework.ToolsSetup
import com.google.devtools.build.lib.vfs.Path
import java.nio.file.Path
import java.util.concurrent.ExecutorService

/**
 * Implementation of [BlackBoxTestEnvironment] with the code of initializing Bazel blackbox
 * test environment.
 */
class BlackBoxTestEnvironmentImpl : BlackBoxTestEnvironment() {
    @Throws(java.lang.Exception::class)
    public override fun prepareEnvironment(
        testName: String?,
        tools: com.google.common.collect.ImmutableList<ToolsSetup?>?,
        executorService: ExecutorService?
    ): BlackBoxTestContext {
        val binaryPath: Path = RunfilesUtil.find("io_bazel/src/bazel")

        val testContext: BlackBoxTestContext =
            BlackBoxTestContext(
                testName, "bazel", binaryPath, mutableMapOf<String?, String?>(), executorService
            )
        // Any Bazel command requires that workspace is already set up.
        testContext.write("MODULE.bazel")
        val defaultLockfile: Path = RunfilesUtil.find("io_bazel/src/test/tools/bzlmod/MODULE.bazel.lock")
        java.nio.file.Files.copy(defaultLockfile, testContext.getWorkDir().resolve("MODULE.bazel.lock"))

        val allTools: MutableList<ToolsSetup> =
            com.google.common.collect.Lists.newArrayList<ToolsSetup?>(DefaultToolsSetup())
        allTools.addAll(tools)
        for (tool in allTools) {
            tool.setup(testContext)
        }

        com.google.devtools.build.lib.blackbox.framework.PathUtils.setTreeWritable(testContext.getWorkDir())

        return testContext
    }
}
