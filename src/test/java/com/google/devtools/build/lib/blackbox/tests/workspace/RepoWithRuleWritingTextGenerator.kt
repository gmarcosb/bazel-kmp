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
//
package com.google.devtools.build.lib.blackbox.tests.workspace

import com.google.devtools.build.lib.bazel.repository.decompressor.DecompressorDescriptor.Builder.build
import com.google.devtools.build.lib.vfs.Path
import java.io.IOException
import java.nio.file.Path

/**
 * Generator of the test local repository with:
 * 
 * 
 * helper.bzl file, that contains a write_to_file rule, which writes some text to the output
 * file.
 * 
 * 
 * empty MODULE.bazel file,
 * 
 * 
 * BUILD file, with write_to_file target and pkg_tar target for packing the contents of the
 * generated repository.
 * 
 * 
 * Intended to be used by workspace tests.
 */
class RepoWithRuleWritingTextGenerator internal constructor(root: Path?) {
    private val root: Path?
    private var target: String
    private var outputText: String?
    private var outFile: String?
    private var generateBuildFile: Boolean

    /**
     * Generator constructor
     * 
     * @param root - the Path to the directory, where the repository contents should be placed
     */
    init {
        this.root = root
        this.target = TARGET
        this.outputText = HELLO
        this.outFile = OUT_FILE
        generateBuildFile = true
    }

    /**
     * Specifies the text to be put into generated file by write_to_file rule target
     * 
     * @param text - text to be put into a file
     * @return this generator
     */
    fun withOutputText(text: String?): RepoWithRuleWritingTextGenerator {
        outputText = text
        return this
    }

    /**
     * Specifies the name of the write_to_file target in the generated repository
     * 
     * @param name - name of the target
     * @return this generator
     */
    fun withTarget(name: String): RepoWithRuleWritingTextGenerator {
        target = name
        return this
    }

    /**
     * Specifies the output file name of the write_to_file rule target
     * 
     * @param name - output file name
     * @return this generator
     */
    fun withOutFile(name: String?): RepoWithRuleWritingTextGenerator {
        outFile = name
        return this
    }

    /**
     * Specifies that BUILD file should not be generated
     * 
     * @return this generator
     */
    fun skipBuildFile(): RepoWithRuleWritingTextGenerator {
        generateBuildFile = false
        return this
    }

    /**
     * Generates the repository: MODULE.bazel, BUILD, and helper.bzl files.
     * 
     * @return repository directory
     * @throws IOException if was not able to create or write to files
     */
    @Throws(IOException::class)
    fun setupRepository(): Path? {
        val workspace: Path =
            com.google.devtools.build.lib.blackbox.framework.PathUtils.writeFileInDir(root, "MODULE.bazel")
        com.google.devtools.build.lib.blackbox.framework.PathUtils.writeFileInDir(root, HELPER_FILE, WRITE_TEXT_TO_FILE)
        if (generateBuildFile) {
            com.google.devtools.build.lib.blackbox.framework.PathUtils.writeFileInDir(
                root,
                "BUILD",
                "load(\"@bazel_tools//tools/build_defs/pkg:pkg.bzl\", \"pkg_tar\")",
                loadRule(""),
                callRule(target, outFile, outputText),
                String.format("pkg_tar(name = \"%s\", srcs = glob([\"*\"]),)", this.pkgTarTarget)
            )
        }
        return workspace.getParent()
    }

    val pkgTarTarget: String
        /** @return name of the generated pkg_tar target
         */
        get() = "pkg_tar_" + target

    companion object {
        const val HELPER_FILE: String = "helper.bzl"
        const val RULE_NAME: String = "write_to_file"
        const val HELLO: String = "HELLO"
        const val TARGET: String = "write_text"
        const val OUT_FILE: String = "out"

        val WRITE_TEXT_TO_FILE: String = ("def _impl(ctx):\n"
                + "  out = ctx.actions.declare_file(ctx.attr.filename)\n"
                + "  ctx.actions.write(out, ctx.attr.text)\n"
                + "  return [DefaultInfo(files = depset([out]))]\n"
                + "\n"
                + RULE_NAME
                + " = rule(\n"
                + "    implementation = _impl,\n"
                + "    attrs = {\n"
                + "        \"filename\": attr.string(default = \"out\"),\n"
                + "        \"text\": attr.string()\n"
                + "    }\n"
                + ")")

        /**
         * Returns the text to be put into a header of Starlark file, which is going to use write_to_file
         * rule from the @repoName repository
         * 
         * @param repoName the name of the repository
         * @return load statement text
         */
        fun loadRule(repoName: String?): String? {
            return String.format("load('%s//:%s', '%s')", repoName, HELPER_FILE, RULE_NAME)
        }

        /**
         * Returns the text with the write_to_file target
         * 
         * @param name target name
         * @param filename name of the output file
         * @param text text to be put into output file
         * @return the write_to_file target definition
         */
        fun callRule(name: String?, filename: String?, text: String?): String? {
            return String.format(
                "%s(name = '%s', filename = '%s', text ='%s')", RULE_NAME, name, filename, text
            )
        }
    }
}
