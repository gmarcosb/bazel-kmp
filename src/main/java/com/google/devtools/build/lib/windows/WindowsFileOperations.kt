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
package com.google.devtools.build.lib.windows

import com.google.devtools.build.lib.jni.JniLoader.loadJni
import com.google.devtools.build.lib.vfs.FileSystem.NotASymlinkException
import com.google.devtools.build.lib.vfs.PathFragment
import com.google.devtools.build.lib.windows.WindowsPathOperations
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.DirectoryNotEmptyException

/** File operations on Windows.  */
object WindowsFileOperations {
    // A note about UNC paths and path prefixes on Windows. The prefixes can be:
    // - "\\?\", meaning it's a UNC path that is passed to user mode unicode WinAPI functions
    //   (e.g. CreateFileW) or a return value of theirs (e.g. GetLongPathNameW); this is the
    //   prefix we'll most often see
    // - "\??\", meaning it's Device Object path; it's mostly only used by kernel/driver functions
    //   but we may come across it when resolving junction targets, as the target's path is
    //   specified with this prefix, see usages of DeviceIoControl with FSCTL_GET_REPARSE_POINT
    // - "\\.\", meaning it's a Device Object path again; both "\??\" and "\\.\" are shorthands
    //   for the "\DosDevices\" Object Directory, so "\\.\C:" and "\??\C:" and "\DosDevices\C:"
    //   and "C:\" all mean the same thing, but functions like CreateFileW don't understand the
    //   fully qualified device path, only the shorthand versions; the difference between "\\.\"
    //   is "\??\" is not entirely clear (one is not available while Windows is booting, but
    //   that only concerns device drivers) but we most likely won't come across them anyway
    // Some of this is documented here:
    // - https://msdn.microsoft.com/en-us/library/windows/hardware/ff557762(v=vs.85).aspx
    // - https://msdn.microsoft.com/en-us/library/windows/hardware/ff565384(v=vs.85).aspx
    // - http://stackoverflow.com/questions/23041983
    // - http://stackoverflow.com/questions/14482421
    init {
        com.google.devtools.build.lib.jni.JniLoader.loadJni()
    }

    // Keep IS_SYMLINK_OR_JUNCTION_* values in sync with src/main/native/windows/file.cc.
    private const val IS_SYMLINK_OR_JUNCTION_SUCCESS = 0

    // IS_SYMLINK_OR_JUNCTION_ERROR = 1;
    private const val IS_SYMLINK_OR_JUNCTION_DOES_NOT_EXIST = 2

    // Keep GET_CHANGE_TIME_* values in sync with src/main/native/windows/file.cc.
    private const val GET_CHANGE_TIME_SUCCESS = 0

    //  private static final int GET_CHANGE_TIME_ERROR = 1;
    private const val GET_CHANGE_TIME_DOES_NOT_EXIST = 2
    private const val GET_CHANGE_TIME_ACCESS_DENIED = 3

    // Keep CREATE_JUNCTION_* values in sync with src/main/native/windows/file.h.
    private const val CREATE_JUNCTION_SUCCESS = 0

    // CREATE_JUNCTION_ERROR = 1;
    private const val CREATE_JUNCTION_TARGET_NAME_TOO_LONG = 2
    private const val CREATE_JUNCTION_ALREADY_EXISTS_WITH_DIFFERENT_TARGET = 3
    private const val CREATE_JUNCTION_ALREADY_EXISTS_BUT_NOT_A_JUNCTION = 4
    private const val CREATE_JUNCTION_ACCESS_DENIED = 5
    private const val CREATE_JUNCTION_DISAPPEARED = 6
    private const val CREATE_JUNCTION_NOT_SUPPORTED = 7

    // Keep CREATE_SYMLINK_* values in sync with src/main/native/windows/file.h.
    private const val CREATE_SYMLINK_SUCCESS = 0

    // CREATE_SYMLINK_ERROR = 1;
    private const val CREATE_SYMLINK_TARGET_IS_DIRECTORY = 2

    // Keep DELETE_PATH_* values in sync with src/main/native/windows/file.h.
    private const val DELETE_PATH_SUCCESS = 0

    // DELETE_PATH_ERROR = 1;
    private const val DELETE_PATH_DOES_NOT_EXIST = 2
    private const val DELETE_PATH_DIRECTORY_NOT_EMPTY = 3
    private const val DELETE_PATH_ACCESS_DENIED = 4

    // Keep READ_SYMLINK_OR_JUNCTION_* values in sync with src/main/native/windows/file.h.
    private const val READ_SYMLINK_OR_JUNCTION_SUCCESS = 0

    // READ_SYMLINK_OR_JUNCTION_ERROR = 1;
    private const val READ_SYMLINK_OR_JUNCTION_ACCESS_DENIED = 2
    private const val READ_SYMLINK_OR_JUNCTION_DOES_NOT_EXIST = 3
    private const val READ_SYMLINK_OR_JUNCTION_NOT_A_LINK = 4

    private external fun nativeIsSymlinkOrJunction(
        path: String?, result: BooleanArray?, error: Array<String?>?
    ): Int

    private external fun nativeGetChangeTime(
        path: String?, followReparsePoints: Boolean, result: LongArray?, error: Array<String?>?
    ): Int

    private external fun nativeCreateJunction(name: String?, target: String?, error: Array<String?>?): Int

    private external fun nativeCreateSymlink(name: String?, target: String?, error: Array<String?>?): Int

    private external fun nativeReadSymlinkOrJunction(
        name: String?, result: Array<String?>?, error: Array<String?>?
    ): Int

    private external fun nativeDeletePath(path: String?, error: Array<String?>?): Int

    /** Determines whether `path` is a junction point or directory symlink.  */
    @kotlin.jvm.JvmStatic
    @Throws(IOException::class)
    fun isSymlinkOrJunction(path: String): Boolean {
        val result = booleanArrayOf(false)
        val error = arrayOf<String?>(null)
        when (nativeIsSymlinkOrJunction(WindowsPathOperations.asLongPath(path), result, error)) {
            IS_SYMLINK_OR_JUNCTION_SUCCESS -> return result[0]
            IS_SYMLINK_OR_JUNCTION_DOES_NOT_EXIST -> throw FileNotFoundException(path)
            else -> {}
        }
        throw IOException(java.lang.String.format("Cannot tell if '%s' is link: %s", path, error[0]))
    }

    /** Returns the time at which the file was last changed, including metadata changes.  */
    @Throws(IOException::class)
    fun getLastChangeTime(path: String, followReparsePoints: Boolean): Long {
        val result = longArrayOf(0)
        val error = arrayOf<String?>(null)
        when (nativeGetChangeTime(
            WindowsPathOperations.asLongPath(path), followReparsePoints, result, error
        )) {
            GET_CHANGE_TIME_SUCCESS -> return result[0]
            GET_CHANGE_TIME_DOES_NOT_EXIST -> throw FileNotFoundException(path)
            GET_CHANGE_TIME_ACCESS_DENIED -> throw AccessDeniedException(path)
            else -> {}
        }
        throw IOException(java.lang.String.format("Cannot get last change time of '%s': %s", path, error[0]))
    }

    /**
     * Creates a junction at `name`, pointing to `target`.
     * 
     * 
     * Both `name` and `target` may be Unix-style Windows paths (i.e. use forward slashes), and
     * they don't need to have a UNC prefix, not even if they are longer than `MAX_PATH`. The
     * underlying logic will take care of adding the prefixes if necessary.
     * 
     * @throws IOException if some error occurs
     */
    @kotlin.jvm.JvmStatic
    @Throws(IOException::class)
    fun createJunction(name: String, target: String) {
        val error = arrayOf<String?>(null)
        when (nativeCreateJunction(
            WindowsPathOperations.asLongPath(name), WindowsPathOperations.asLongPath(target), error
        )) {
            CREATE_JUNCTION_SUCCESS -> return
            CREATE_JUNCTION_TARGET_NAME_TOO_LONG -> error[0] = "target name is too long"
            CREATE_JUNCTION_ALREADY_EXISTS_WITH_DIFFERENT_TARGET -> error[0] =
                "junction already exists with different target"

            CREATE_JUNCTION_ALREADY_EXISTS_BUT_NOT_A_JUNCTION -> error[0] =
                "a file or directory already exists at the junction's path"

            CREATE_JUNCTION_ACCESS_DENIED -> error[0] = "access is denied"
            CREATE_JUNCTION_DISAPPEARED -> error[0] = "the junction's path got modified unexpectedly"
            CREATE_JUNCTION_NOT_SUPPORTED -> error[0] = "filesystem does not support junctions"
            else -> {}
        }
        throw IOException(
            java.lang.String.format("Cannot create junction (name=%s, target=%s): %s", name, target, error[0])
        )
    }

    @kotlin.jvm.JvmStatic
    @Throws(IOException::class)
    fun createSymlink(name: String, target: String) {
        val error = arrayOf<String?>(null)
        when (nativeCreateSymlink(
            WindowsPathOperations.asLongPath(name), WindowsPathOperations.asLongPath(target), error
        )) {
            CREATE_SYMLINK_SUCCESS -> return
            CREATE_SYMLINK_TARGET_IS_DIRECTORY -> error[0] = "symlink target is a directory, use a junction"
            else -> {}
        }
        throw IOException(
            java.lang.String.format("Cannot create symlink (name=%s, target=%s): %s", name, target, error[0])
        )
    }

    @Throws(IOException::class)
    fun readSymlinkOrJunction(name: String): String? {
        val target = arrayOf<String?>(null)
        val error = arrayOf<String?>(null)
        when (nativeReadSymlinkOrJunction(WindowsPathOperations.asLongPath(name), target, error)) {
            READ_SYMLINK_OR_JUNCTION_SUCCESS -> return WindowsPathOperations.removeUncPrefixAndUseSlashes(target[0])
            READ_SYMLINK_OR_JUNCTION_ACCESS_DENIED -> throw AccessDeniedException(name)
            READ_SYMLINK_OR_JUNCTION_DOES_NOT_EXIST -> throw FileNotFoundException(name)
            READ_SYMLINK_OR_JUNCTION_NOT_A_LINK -> throw NotASymlinkException(PathFragment.Companion.create(name))
            else ->         // This is READ_SYMLINK_OR_JUNCTION_ERROR (1). The JNI code puts a custom message in
                // 'error[0]'.
                throw IOException(java.lang.String.format("Cannot read link (name=%s): %s", name, error[0]))
        }
    }

    @kotlin.jvm.JvmStatic
    @Throws(IOException::class)
    fun deletePath(path: String): Boolean {
        val error = arrayOf<String?>(null)
        val result = nativeDeletePath(WindowsPathOperations.asLongPath(path), error)
        when (result) {
            DELETE_PATH_SUCCESS -> return true
            DELETE_PATH_DOES_NOT_EXIST -> return false
            DELETE_PATH_DIRECTORY_NOT_EMPTY -> throw DirectoryNotEmptyException(path)
            DELETE_PATH_ACCESS_DENIED -> throw AccessDeniedException(path)
            else ->         // This is DELETE_PATH_ERROR (1). The JNI code puts a custom message in 'error[0]'.
                throw IOException(java.lang.String.format("Cannot delete path '%s': %s", path, error[0]))
        }
    }
}
