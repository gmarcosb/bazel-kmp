// Copyright 2016 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.vfs.Root

/**
 * Represents the relevant directories for the server: the location of the embedded binaries and the
 * output directories.
 */
@com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable
class ServerDirectories @kotlin.jvm.JvmOverloads constructor(
    installBase: com.google.devtools.build.lib.vfs.Path?,
    outputBase: com.google.devtools.build.lib.vfs.Path?,
    outputUserRoot: com.google.devtools.build.lib.vfs.Path?,
    execRootBase: com.google.devtools.build.lib.vfs.Path? = outputBase.getRelative(
        EXECROOT
    ),
    virtualSourceRoot: Root? = null,
    installMD5: String? = null
) {
    /** Where Bazel gets unpacked.  */
    private val installBase: com.google.devtools.build.lib.vfs.Path?

    /** The content hash of everything in installBase.  */
    private val installMD5: com.google.common.hash.HashCode?

    /** The root of the temp and output trees.  */
    private val outputBase: com.google.devtools.build.lib.vfs.Path?

    /** Top-level user output directory; used, e.g., as default location for caches.  */
    private val outputUserRoot: com.google.devtools.build.lib.vfs.Path?

    private val execRootBase: com.google.devtools.build.lib.vfs.Path?
    private val virtualSourceRoot: Root? // Null if the source root is not virtualized.

    // TODO(bazel-team): Use a builder to simplify/unify these constructors. This makes it easier to
    // have sensible defaults, e.g. execRootBase = outputBase + "/execroot". Then reorder the fields
    // to be consistent throughout this class.
    init {
        this.installBase = installBase
        this.installMD5 = toMD5HashCode(installMD5)
        this.outputBase = outputBase
        this.outputUserRoot = outputUserRoot
        this.execRootBase = execRootBase
        this.virtualSourceRoot = virtualSourceRoot
    }

    /** Returns the installation base directory.  */
    fun getInstallBase(): com.google.devtools.build.lib.vfs.Path? {
        return installBase
    }

    /**
     * Returns the MD5 content hash of the blaze binary (includes deploy JAR, embedded binaries, and
     * anything else that ends up in the install_base).
     */
    fun getInstallMD5(): com.google.common.hash.HashCode? {
        return installMD5
    }

    /**
     * Returns the base of the output tree, which hosts all build and scratch output for a user and
     * workspace.
     */
    fun getOutputBase(): com.google.devtools.build.lib.vfs.Path? {
        return outputBase
    }

    /**
     * Returns the root directory for user output. In particular default caches will be located here.
     */
    fun getOutputUserRoot(): com.google.devtools.build.lib.vfs.Path? {
        return outputUserRoot
    }

    /**
     * Parent of all execution roots.
     * 
     * 
     * By default, this is a folder called [execroot][.EXECROOT] in [ ][.getOutputBase].
     */
    fun getExecRootBase(): com.google.devtools.build.lib.vfs.Path? {
        return execRootBase
    }

    /**
     * Returns a stable, virtual root that (if present) should be used as the effective package path
     * for all commands during the server's lifetime.
     * 
     * 
     * If present, the server's [com.google.devtools.build.lib.vfs.FileSystem] is responsible
     * for translating paths under this root to the actual requested `--package_path` for a
     * given command.
     */
    fun getVirtualSourceRoot(): Root? {
        return virtualSourceRoot
    }

    companion object {
        const val EXECROOT: String = "execroot"

        private fun toMD5HashCode(installMD5: String?): com.google.common.hash.HashCode? {
            if (com.google.common.base.Strings.isNullOrEmpty(installMD5)) {
                return null
            }
            val hash: com.google.common.hash.HashCode = com.google.common.hash.HashCode.fromString(installMD5)
            com.google.common.base.Preconditions.checkArgument(
                hash.bits() == com.google.common.hash.Hashing.md5().bits(), "Hash '%s' has %s bits", hash, hash.bits()
            )
            return hash
        }
    }
}
