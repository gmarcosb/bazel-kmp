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

import com.google.devtools.build.lib.blackbox.framework.BlackBoxTestContext
import com.google.devtools.build.lib.blackbox.framework.ProcessResult
import com.google.devtools.build.lib.vfs.Path
import java.nio.file.Path

/**
 * Helper class for working with local git repository in tests. Should not be used outside ot tests.
 */
internal class GitRepositoryHelper(context: BlackBoxTestContext, root: Path) {
    private val context: BlackBoxTestContext
    private val root: Path

    /**
     * Constructs the helper.
     * 
     * @param context [BlackBoxTestContext] for running git process
     * @param root working directory for running git process, expected to be existing.
     */
    init {
        this.context = context
        this.root = root
    }

    /**
     * Calls 'git init' and 'git config' for specifying test user and email.
     * 
     * @throws Exception related to the invocation of the external git process (like IOException or
     * TimeoutException) or ProcessRunnerException if the process returned not expected return
     * code.
     */
    @Throws(java.lang.Exception::class)
    fun init() {
        runGit("init")
        runGit("config", "user.email", "me@example.com")
        runGit("config", "user.name", "E X Ample")
        runGit("commit", "--allow-empty", "-m", "Initial commit")
        runGit("branch", "-M", "main")
    }

    /**
     * Recursively updates git index for all the files and directories under the working directory.
     * 
     * @throws Exception related to the invocation of the external git process (like IOException or
     * TimeoutException) or ProcessRunnerException if the process returned not expected return
     * code.
     */
    @Throws(java.lang.Exception::class)
    fun addAll() {
        runGit("add", "-A")
    }

    /**
     * Commits all staged changed.
     * 
     * @param commitMessage commit message
     * @throws Exception related to the invocation of the external git process (like IOException or
     * TimeoutException) or ProcessRunnerException if the process returned not expected return
     * code.
     */
    @Throws(java.lang.Exception::class)
    fun commit(commitMessage: String?) {
        runGit("commit", "-m", commitMessage)
    }

    /**
     * Tags the HEAD commit.
     * 
     * @param tagName tag name
     * @throws Exception related to the invocation of the external git process (like IOException or
     * TimeoutException) or ProcessRunnerException if the process returned not expected return
     * code.
     */
    @Throws(java.lang.Exception::class)
    fun tag(tagName: String?) {
        runGit("tag", tagName)
    }

    /**
     * Creates the new branch with the specified name at HEAD.
     * 
     * @param branchName branch name
     * @throws Exception related to the invocation of the external git process (like IOException or
     * TimeoutException) or ProcessRunnerException if the process returned not expected return
     * code.
     */
    @Throws(java.lang.Exception::class)
    fun createNewBranch(branchName: String?) {
        runGit("checkout", "-b", branchName)
    }

    /**
     * Deletes the local branch with the specified name.
     * 
     * @param branchName branch name
     * @throws Exception related to the invocation of the external git process (like IOException or
     * TimeoutException) or ProcessRunnerException if the process returned not expected return
     * code.
     */
    @Throws(java.lang.Exception::class)
    fun deleteBranch(branchName: String?) {
        runGit("branch", "-D", branchName)
    }

    /**
     * Checks out specified revision or reference.
     * 
     * @param ref reference to check out
     * @throws Exception related to the invocation of the external git process (like IOException or
     * TimeoutException) or ProcessRunnerException if the process returned not expected return
     * code.
     */
    @Throws(java.lang.Exception::class)
    fun checkout(ref: String?) {
        runGit("checkout", ref)
    }

    @get:Throws(java.lang.Exception::class)
    val head: String?
        /**
         * Returns the HEAD's commit hash.
         * 
         * @throws Exception related to the invocation of the external git process (like IOException or
         * TimeoutException) or ProcessRunnerException if the process returned not expected return
         * code.
         */
        get() = runGit("rev-parse", "--short", "HEAD")

    @Throws(java.lang.Exception::class)
    private fun runGit(vararg arguments: String?): String? {
        val result: ProcessResult = context.runBinary(root, "git", false, *arguments)
        return result.outString()
    }
}
