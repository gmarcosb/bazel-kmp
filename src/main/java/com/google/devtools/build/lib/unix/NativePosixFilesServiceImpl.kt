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
package com.google.devtools.build.lib.unix

import com.google.devtools.build.lib.bugreport.BugReport
import com.google.devtools.build.lib.bugreport.BugReport.sendNonFatalBugReport
import com.google.devtools.build.lib.jni.JniLoader.loadJni
import com.google.devtools.build.lib.unix.NativePosixFilesService
import com.google.devtools.build.lib.unix.NativePosixFilesService.Stat
import com.google.devtools.build.lib.unix.NativePosixFilesService.StatErrorHandling
import java.io.IOException

/** Implementation of [NativePosixFilesService].  */
class NativePosixFilesServiceImpl : NativePosixFilesService {
    @Throws(IOException::class)
    external override fun readlink(path: String?): String?

    @Throws(IOException::class)
    external override fun chmod(path: String?, mode: Int)

    @Throws(IOException::class)
    external override fun symlink(oldpath: String?, newpath: String?)

    @Throws(IOException::class)
    external override fun link(oldpath: String?, newpath: String?)

    @Throws(IOException::class)
    override fun stat(path: String?, errorHandling: StatErrorHandling): Stat? {
        return stat(path, errorHandling.getCode())
    }

    @Throws(IOException::class)
    private external fun stat(path: String?, errorHandling: Char): Stat?

    @Throws(IOException::class)
    override fun lstat(path: String?, errorHandling: StatErrorHandling): Stat? {
        return lstat(path, errorHandling.getCode())
    }

    @Throws(IOException::class)
    private external fun lstat(path: String?, errorHandling: Char): Stat?

    @Throws(IOException::class)
    external override fun utimensat(path: String?, now: Boolean, epochMilli: Long)

    @Throws(IOException::class)
    external override fun mkdir(path: String?, mode: Int): Boolean

    @Throws(IOException::class)
    external override fun readdir(path: String?): Array<com.google.devtools.build.lib.unix.NativePosixFilesService.Dirent?>?

    @Throws(IOException::class)
    external override fun rename(oldpath: String?, newpath: String?)

    @Throws(IOException::class)
    external override fun remove(path: String?): Boolean

    @Throws(IOException::class)
    external override fun mkfifo(path: String?, mode: Int)

    @Throws(IOException::class)
    external override fun getxattr(path: String?, name: String?): ByteArray?

    @Throws(IOException::class)
    external override fun lgetxattr(path: String?, name: String?): ByteArray?

    @Throws(IOException::class)
    external override fun deleteTreesBelow(dir: String?)

    companion object {
        init {
            com.google.devtools.build.lib.jni.JniLoader.loadJni()
        }

        /** Logs a path string that does not have a Latin-1 coder. Called from JNI.  */
        private fun logBadPath(path: String?) {
            BugReport.sendNonFatalBugReport(
                java.lang.IllegalStateException("Path string does not have a Latin-1 coder: %s".formatted(path))
            )
        }
    }
}
