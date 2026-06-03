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
package com.google.devtools.build.lib.analysis

import com.google.devtools.build.lib.actions.ArtifactRoot

/**
 * Encapsulates the directories related to a workspace.
 * 
 * 
 * A `workspace>` is a directory tree containing the source files you want to build.
 * 
 * 
 * The `workspace Path` object this class stores is the workspace's root directory, which
 * contains a `WORKSPACE` file that marks and configures the workspace. When you build `//my:project`, this signifies a target named `project` in a `BUILD` file in the
 * `my` subdirectory under the workspace root. You can find the workspace root directory by
 * running `$ bazel info | grep workspace`.
 * 
 * 
 * The `outputBase` is where all workspace output is written. This includes both build
 * outputs and internal files Bazel uses to support builds (like the action cache, log files, and
 * external repository mappings). This path is only meaningful for core Bazel devs: it's not part of
 * the public user API. This path is not under the workspace root (since its purpose isn't to host
 * workspace source files). This appears as `_bazel_$USER/$SOME_HASH/` under some local file
 * system root. Exact paths vary depending on what machine you're running Bazel on. You can find
 * this path by running `$ bazel info | grep output_base`.
 * 
 * 
 * The `execRoot` is the working directory for all spawned tools. It includes both the
 * subdirectory where Bazel writes build outputs (the `outputPath`) and the symlink forest
 * Bazel constructs to map workspace source files the spawned tool can access when it runs. It
 * generally looks like `$OUTPUT_BASE/execroot/$WORKSPACE_IDENTIFIER`. You can find this path
 * by running `$ bazel info | grep execution_root`.
 * 
 * 
 * The `outputPath` (confusingly similar name to `outputBase`, alas) is the root path
 * where Bazel writes build outputs. In other words, any action transforming a source file into a
 * generated output writes that output under this path. It generally looks like `$OUTPUT_BASE/execroot/$WORKSPACE_IDENTIFIER/bazel-out`. You can find this path by running `$ bazel info | grep output_path`.
 * 
 * 
 * Care must be taken to avoid multiple Bazel instances trying to write to the same output tree.
 * This is enforced by requiring a 1:1 correspondence between a running Bazel instance and an output
 * base.
 * 
 * 
 * If the user does not qualify an output base directory, the startup code will derive it
 * deterministically from the workspace. Note also that while the Bazel server process runs with the
 * workspace directory as its working directory, the client process may have a different working
 * directory, typically a subdirectory.
 * 
 * 
 * Do not put shortcuts to specific files here!
 */
@Immutable
class BlazeDirectories(serverDirectories: ServerDirectories, workspace: Path?, productName: String) {
    private val serverDirectories: ServerDirectories

    /** Workspace root and server CWD.  */
    private val workspace: Path?

    private val blazeExecRoot: Path?

    // These two are kept to avoid creating new objects every time they are accessed. This showed up
    // in a profiler.
    private val blazeOutputPath: Path?
    private val localOutputPath: Path?
    private val productName: String?

    init {
        this.serverDirectories = serverDirectories
        this.workspace = workspace
        this.productName = productName
        val outputBase: Path = serverDirectories.getOutputBase()
        if (com.google.common.base.Ascii.equalsIgnoreCase(productName, "blaze")) {
            val useDefaultExecRootName =
                this.workspace == null || this.workspace.getParentDirectory() == null
            if (useDefaultExecRootName) {
                // TODO(bazel-team): if workspace is null execRoot should be null, but at the moment there
                // is a lot of code that depends on it being non-null.
                this.blazeExecRoot =
                    outputBase.getChild(ServerDirectories.EXECROOT).getChild(DEFAULT_EXEC_ROOT)
            } else {
                this.blazeExecRoot =
                    outputBase.getChild(ServerDirectories.EXECROOT).getChild(workspace.getBaseName())
            }
            this.blazeOutputPath = blazeExecRoot.getRelative(getRelativeOutputPath())
        } else {
            this.blazeExecRoot = null
            this.blazeOutputPath = null
        }
        this.localOutputPath = outputBase.getRelative(getRelativeOutputPath())
    }

    fun getServerDirectories(): ServerDirectories {
        return serverDirectories
    }

    /** Returns the installation base directory.  */
    fun getInstallBase(): Path {
        return serverDirectories.getInstallBase()
    }

    /**
     * Returns the workspace directory to use for build artifacts.
     * 
     * 
     * It may effectively differ from the working directory. Please use [ ][.getWorkingDirectory] for writes within the working directory.
     */
    fun getWorkspace(): Path? {
        // Make sure to use the same file system as exec root.
        return if (workspace != null)
            getExecRootBase().getFileSystem().getPath(workspace.asFragment())
        else
            null
    }

    /** Returns working directory of the server.  */
    fun getWorkingDirectory(): Path? {
        return workspace
    }


    /** Returns if the workspace directory is a valid workspace.  */
    fun inWorkspace(): Boolean {
        return this.workspace != null
    }

    /**
     * Returns the base of the output tree, which hosts all build and scratch output for a user and
     * workspace.
     */
    fun getOutputBase(): Path {
        return serverDirectories.getOutputBase()
    }

    /** Returns the execution root base path with no workspace name fragment.  */
    fun getExecRootBase(): Path {
        return serverDirectories.getExecRootBase()
    }

    /**
     * Returns the local execution root of Google-internal Blaze.
     * 
     * 
     * This method throws [NullPointerException] in Bazel. Use [.getExecRoot] instead.
     */
    fun getBlazeExecRoot(): Path? {
        return com.google.common.base.Preconditions.checkNotNull<Path?>(blazeExecRoot, "No Blaze exec root in Bazel")
    }

    /**
     * Returns the execution root for a particular repository. This is the directory underneath which
     * Blaze builds the source symlink forest, to represent the merged view of different workspaces
     * specified with --package_path.
     */
    fun getExecRoot(workspaceName: String?): Path {
        return getExecRootBase().getRelative(workspaceName)
    }

    /**
     * Returns the local output path of Google-internal Blaze.
     * 
     * 
     * This method throws [NullPointerException] in Bazel. Use [.getOutputPath]
     * instead.
     */
    fun getBlazeOutputPath(): Path? {
        return com.google.common.base.Preconditions.checkNotNull<Path?>(
            blazeOutputPath,
            "No Blaze output path in Bazel"
        )
    }

    /** Returns the output path used by this Blaze instance.  */
    fun getOutputPath(workspaceName: String?): Path {
        return getExecRoot(workspaceName).getRelative(getRelativeOutputPath())
    }

    /** Returns the local output path used by this Blaze instance.  */
    fun getLocalOutputPath(): Path? {
        return localOutputPath
    }

    /**
     * Returns the directory where actions can store temporary files (such as their stdout and stderr)
     * during a build. If the directory already exists, the directory is cleaned.
     */
    fun getActionTempsDirectory(execRoot: Path): Path {
        return execRoot.getRelative(getRelativeOutputPath()).getRelative("_tmp/actions")
    }

    /** Returns the installed embedded binaries directory, under the shared installBase location.  */
    fun getEmbeddedBinariesRoot(): Path {
        return getInstallBase()
    }

    /**
     * Returns the configuration-independent root where the build-data should be placed, given the
     * [BlazeDirectories] of this server instance. Nothing else should be placed here.
     * 
     * 
     * Note that, for historic reasons, this method is also used to determine where coverage data
     * ("_coverage") should be stored.
     */
    fun getBuildDataDirectory(workspaceName: String?): ArtifactRoot {
        return ArtifactRoot.asDerivedRoot(
            getExecRoot(workspaceName), RootType.OUTPUT, getRelativeOutputPath(productName)
        )
    }

    /**
     * Returns the MD5 content hash of the blaze binary (includes deploy JAR, embedded binaries, and
     * anything else that ends up in the install_base).
     */
    fun getInstallMD5(): com.google.common.hash.HashCode {
        return serverDirectories.getInstallMD5()
    }

    /**
     * Returns the directory where Bazel writes build outputs, relative to the execRoot.
     * 
     * 
     * For example: `"bazel-out"`.
     */
    fun getRelativeOutputPath(): String? {
        return getRelativeOutputPath(productName)
    }

    fun getProductName(): String? {
        return productName
    }

    /** Convenience method for [ServerDirectories.getVirtualSourceRoot].  */
    fun getVirtualSourceRoot(): Root? {
        return serverDirectories.getVirtualSourceRoot()
    }

    companion object {
        private const val DEFAULT_EXEC_ROOT = "default-exec-root"

        /**
         * Returns the directory where Bazel writes build outputs, relative to the execRoot.
         * 
         * 
         * For example: `"bazel-out"`.
         */
        fun getRelativeOutputPath(productName: String?): String? {
            return (productName + "-out").intern()
        }
    }
}
