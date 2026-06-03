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
package com.google.devtools.build.lib.generatedprojecttest.util

import com.google.devtools.build.lib.testutil.BuildRuleBuilder
import com.google.devtools.build.lib.testutil.Scratch
import java.io.IOException

/**
 * A builder that generates whole test projects in a scratch file system.
 */
// TODO(blaze-team): (2012) generate valid parameterized BUILD rules.
// TODO(blaze-team): (2012) generate any required src or data or other files.
class TestProjectBuilder @kotlin.jvm.JvmOverloads constructor(// The directory name to use for the workspace.
    private val workspace: String? = WORKSPACE
) {
    /**
     * Returns the [Scratch] containing the Test Project that has been built.
     */
    /** Provides functionality to create and manipulate a scratch file system.  */
    val scratch: Scratch

    /**
     * Creates a builder that will use the given workspace name as the directory.
     */
    /**
     * Creates a builder that will use the default workspace name as the directory.
     */
    init {
        this.scratch = Scratch(String.format("/%s", workspace))
    }

    /**
     * Creates a file in the specified directory with the given content.
     * 
     * @param dirName directory to create a new file within
     * @param fileName file Name of the new file (must be unique within the directory)
     * @param generator FileContentsGenerator implementation
     * @throws IOException if the input dirName was not valid, or the file already existed
     */
    @Throws(IOException::class)
    fun createFileInDir(dirName: String?, fileName: String?, generator: FileContentsGenerator) {
        scratch.file(
            String.format("/%s/%s/%s", workspace, dirName, fileName), generator.getContents()
        )
    }

    /** Creates a dummy file with dummy content in the given package with the given name.  */
    @Throws(IOException::class)
    fun createDummyFileInDir(pkg: String?, fileName: String) {
        scratch.file(String.format("%s/%s", pkg, fileName), dummyContentFor(fileName))
    }

    /**
     * Generates the files necessary for the rule.
     */
    @Throws(IOException::class)
    fun createFilesToGenerate(ruleBuilder: BuildRuleBuilder) {
        for (file in ruleBuilder.getFilesToGenerate()) {
            scratch.file(file, dummyContentFor(file))
        }
    }

    companion object {
        // Default workspace name.
        private const val WORKSPACE = "workspace"

        /** Generates dummy content for a file based on its name and extension.  */
        private fun dummyContentFor(filePath: String): String {
            val fileName: String = filePath.substring(filePath.lastIndexOf('/') + 1)
            val extension: String = fileName.substring(fileName.lastIndexOf('.') + 1)
            if (extension == "bzl"
                || fileName == "BUILD"
                || fileName == "BUILD.bazel"
                || fileName == "WORKSPACE"
                || fileName == "WORKSPACE.bazel"
            ) {
                return "# dummy"
            } else {
                return "dummy"
            }
        }
    }
}
