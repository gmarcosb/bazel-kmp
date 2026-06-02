// Copyright 2017 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.sandbox

import com.google.devtools.build.lib.actions.UserExecException

/** Utility functions for the `linux-sandbox` embedded tool.  */
object LinuxSandboxUtil {
    private val LINUX_SANDBOX = "linux-sandbox" + OsUtils.executableExtension()

    /** Returns whether using the `linux-sandbox` is supported in the command environment.  */
    fun isSupported(blazeWorkspace: BlazeWorkspace): Boolean {
        // We can only use the linux-sandbox if the linux-sandbox exists in the embedded tools.
        // This might not always be the case, e.g. while bootstrapping.
        return getLinuxSandbox(blazeWorkspace) != null
    }

    /** Returns the path of the `linux-sandbox` binary, or null if it doesn't exist.  */
    fun getLinuxSandbox(blazeWorkspace: BlazeWorkspace): com.google.devtools.build.lib.vfs.Path {
        return blazeWorkspace.getBinTools().getEmbeddedPath(LINUX_SANDBOX)
    }

    /**
     * This method does the following things:
     * 
     * 
     *  * If mount source does not exist on the host system, throw an error message
     *  * If mount target exists, check whether the source and target are of the same type
     *  * If mount target does not exist on the host system, throw an error message
     * 
     * 
     * @param bindMounts the bind mounts map with target as key and source as value
     * @throws UserExecException if any of the mount points are not valid
     */
    @Throws(UserExecException::class)
    fun validateBindMounts(bindMounts: MutableMap<com.google.devtools.build.lib.vfs.Path?, com.google.devtools.build.lib.vfs.Path?>) {
        for (bindMount in bindMounts.entries) {
            val source: com.google.devtools.build.lib.vfs.Path = bindMount.value
            val target: com.google.devtools.build.lib.vfs.Path = bindMount.key
            // Mount source should exist in the file system
            if (!source.exists()) {
                throw UserExecException(
                    SandboxHelpers.createFailureDetail(
                        String.format("Mount source '%s' does not exist.", source),
                        Code.MOUNT_SOURCE_DOES_NOT_EXIST
                    )
                )
            }
            // If target exists, but is not of the same type as the source, then we cannot mount it.
            if (target.exists()) {
                val areBothDirectories = source.isDirectory() && target.isDirectory()
                val isSourceFile = source.isFile() || source.isSymbolicLink()
                val isTargetFile = target.isFile() || target.isSymbolicLink()
                val areBothFiles = isSourceFile && isTargetFile
                if (!(areBothDirectories || areBothFiles)) {
                    // Source and target are not of the same type; we cannot mount it.
                    throw UserExecException(
                        SandboxHelpers.createFailureDetail(
                            String.format(
                                "Mount target '%s' is a %s but mount source '%s' is a %s, they must be the"
                                        + " same type.",
                                target,
                                (if (isTargetFile) "file" else "directory"),
                                source,
                                (if (isSourceFile) "file" else "directory")
                            ),
                            Code.MOUNT_SOURCE_TARGET_TYPE_MISMATCH
                        )
                    )
                }
            } else {
                // Mount target should exist in the file system
                throw UserExecException(
                    SandboxHelpers.createFailureDetail(
                        String.format(
                            ("Mount target '%s' does not exist. Bazel only supports bind mounting on top of "
                                    + "existing files/directories. Please create an empty file or directory at "
                                    + "the mount target path according to the type of mount source."),
                            target
                        ),
                        Code.MOUNT_TARGET_DOES_NOT_EXIST
                    )
                )
            }
        }
    }

    /** Returns a newly created inaccessible file underneath the given directory.  */
    @Throws(IOException::class)
    fun getInaccessibleHelperFile(sandboxBase: com.google.devtools.build.lib.vfs.Path): com.google.devtools.build.lib.vfs.Path {
        // The order of the permissions settings calls matters, see
        // https://github.com/bazelbuild/bazel/issues/16364
        val inaccessibleHelperFile: com.google.devtools.build.lib.vfs.Path =
            sandboxBase.getRelative(SandboxHelpers.INACCESSIBLE_HELPER_FILE)
        com.google.devtools.build.lib.vfs.FileSystemUtils.touchFile(inaccessibleHelperFile)
        inaccessibleHelperFile.setExecutable(false)
        inaccessibleHelperFile.setWritable(false)
        inaccessibleHelperFile.setReadable(false)
        return inaccessibleHelperFile
    }

    /** Returns a newly created inaccessible directory underneath the given directory.  */
    @Throws(IOException::class)
    fun getInaccessibleHelperDir(sandboxBase: com.google.devtools.build.lib.vfs.Path): com.google.devtools.build.lib.vfs.Path {
        // The order of the permissions settings calls matters, see
        // https://github.com/bazelbuild/bazel/issues/16364
        val inaccessibleHelperDir: com.google.devtools.build.lib.vfs.Path =
            sandboxBase.getRelative(SandboxHelpers.INACCESSIBLE_HELPER_DIR)
        inaccessibleHelperDir.createDirectory()
        inaccessibleHelperDir.setExecutable(false)
        inaccessibleHelperDir.setWritable(false)
        inaccessibleHelperDir.setReadable(false)
        return inaccessibleHelperDir
    }
}
