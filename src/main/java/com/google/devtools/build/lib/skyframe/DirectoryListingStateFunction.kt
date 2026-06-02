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
package com.google.devtools.build.lib.skyframe

import com.google.devtools.build.lib.skyframe.ExternalFilesHelper
import com.google.devtools.build.lib.skyframe.ExternalFilesHelper.NonexistentImmutableExternalFileException
import com.google.devtools.build.lib.vfs.RootedPath
import com.google.devtools.build.lib.vfs.Symlinks
import com.google.devtools.build.lib.vfs.SyscallCache
import com.google.devtools.build.skyframe.SkyFunction
import com.google.devtools.build.skyframe.SkyFunctionException
import com.google.devtools.build.skyframe.SkyFunctionException.Transience
import com.google.devtools.build.skyframe.SkyKey
import java.io.IOException

/**
 * A [SkyFunction] for [DirectoryListingStateValue]s.
 * 
 * 
 * Merely calls DirectoryListingStateValue#create, but also has special handling for
 * directories outside the package roots (see [ExternalFilesHelper]).
 */
class DirectoryListingStateFunction(externalFilesHelper: ExternalFilesHelper, syscallCache: SyscallCache) :
    SkyFunction {
    private val externalFilesHelper: ExternalFilesHelper

    /**
     * A file-system abstraction to use. This can e.g. be a [DefaultSyscallCache] which helps
     * re-use the results of expensive readdir() operations, that are likely already executed for
     * evaluating globs.
     */
    private val syscallCache: SyscallCache

    init {
        this.externalFilesHelper = externalFilesHelper
        this.syscallCache = syscallCache
    }

    @Throws(DirectoryListingStateFunctionException::class, java.lang.InterruptedException::class)
    override fun compute(skyKey: SkyKey, env: SkyFunction.Environment): DirectoryListingStateValue? {
        val dirRootedPath: RootedPath = skyKey.argument() as RootedPath

        try {
            val fileType: com.google.devtools.build.lib.skyframe.ExternalFilesHelper.FileType? =
                externalFilesHelper.maybeHandleExternalFile(dirRootedPath, env)
            if (env.valuesMissing()) {
                return null
            }
            if (fileType == com.google.devtools.build.lib.skyframe.ExternalFilesHelper.FileType.EXTERNAL_REPO) {
                // Do not use syscallCache as files under repositories get generated during the build,
                // while syscallCache is used independently from Skyframe and generally assumes
                // the file system is frozen at the beginning of the build command.
                return DirectoryListingStateValue.create(dirRootedPath.asPath().readdir(Symlinks.NOFOLLOW))
            }
            return DirectoryListingStateValue.create(syscallCache.readdir(dirRootedPath.asPath()))
        } catch (e: NonexistentImmutableExternalFileException) {
            // DirectoryListingStateValue.key assumes the path exists. This exception here is therefore
            // indicative of a programming bug.
            throw java.lang.IllegalStateException(dirRootedPath.toString(), e)
        } catch (e: IOException) {
            throw DirectoryListingStateFunctionException(e)
        }
    }

    /**
     * Used to declare all the exception types that can be wrapped in the exception thrown by
     * [DirectoryListingStateFunction.compute].
     */
    private class DirectoryListingStateFunctionException
        (e: IOException?) : SkyFunctionException(e, Transience.TRANSIENT)
}
