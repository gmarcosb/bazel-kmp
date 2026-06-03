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
package com.google.devtools.build.lib.integration.util

import com.google.devtools.build.lib.analysis.BlazeDirectories
import kotlin.collections.ArrayList
import kotlin.collections.MutableCollection

/**
 * Performs setup for integration tests.
 */
class IntegrationMock {
    /**
     * Populates the _embedded_binaries/ directory with all files found in any of the directories in
     * [TestConstants.EMBEDDED_SCRIPTS_PATHS] by creating symlinks in
     * [BlazeDirectories.getEmbeddedBinariesRoot] that point to the runfiles tree
     * of the currently running test (as obtained from [BlazeTestUtils.runfilesDir]).
     */
    @Throws(IOException::class)
    fun getIntegrationBinTools(fileSystem: FileSystem, directories: BlazeDirectories): BinTools {
        val embeddedBinariesRoot: Path = directories.getEmbeddedBinariesRoot()
        embeddedBinariesRoot.createDirectoryAndParents()

        val runfiles: Path = fileSystem.getPath(BlazeTestUtils.runfilesDir())
        // Copy over everything in embedded_scripts.
        val files: MutableCollection<Path> = ArrayList<Path>()
        for (embeddedScriptPath in TestConstants.EMBEDDED_SCRIPTS_PATHS) {
            val embeddedScripts: Path = runfiles.getRelative(embeddedScriptPath)
            if (embeddedScripts.exists()) {
                files.addAll(embeddedScripts.getDirectoryEntries())
            } else {
                System.err.println("test does not have " + embeddedScripts)
            }
        }

        for (fromFile in files) {
            try {
                embeddedBinariesRoot.getChild(fromFile.getBaseName()).createSymbolicLink(fromFile)
            } catch (e: IOException) {
                System.err.println("Could not symlink: " + e.message)
            }
        }

        return BinTools.forIntegrationTesting(
            directories,
            TestConstants.EMBEDDED_TOOLS
        )
    }

    companion object {
        @kotlin.jvm.JvmStatic
        fun get(): IntegrationMock {
            return IntegrationMock()
        }
    }
}
