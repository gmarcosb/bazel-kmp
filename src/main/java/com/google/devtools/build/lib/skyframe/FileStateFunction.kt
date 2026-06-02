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

import com.google.devtools.build.lib.actions.FileStateValue

/**
 * A [SkyFunction] for [FileStateValue]s.
 * 
 * 
 * Merely calls FileStateValue#create, but also has special handling for files outside the
 * package roots (see [ExternalFilesHelper]).
 */
class FileStateFunction(
    tsgm: java.util.function.Supplier<TimestampGranularityMonitor?>,
    syscallCache: SyscallCache?,
    externalFilesHelper: ExternalFilesHelper
) : SkyFunction {
    private val tsgm: java.util.function.Supplier<TimestampGranularityMonitor?>
    private val syscallCache: SyscallCache?
    private val externalFilesHelper: ExternalFilesHelper

    init {
        this.tsgm = tsgm
        this.syscallCache = syscallCache
        this.externalFilesHelper = externalFilesHelper
    }

    // InconsistentFilesystemException catch block needs to be separate from IOException catch block
    // below because Java does "single dispatch": the runtime type of e is all that is considered when
    // deciding which overload of FileStateFunctionException() to call.
    @Throws(FileStateFunctionException::class, java.lang.InterruptedException::class)
    override fun compute(skyKey: SkyKey, env: SkyFunction.Environment): FileStateValue? {
        val rootedPath: RootedPath? = skyKey.argument() as RootedPath?

        try {
            val fileType: com.google.devtools.build.lib.skyframe.ExternalFilesHelper.FileType? =
                externalFilesHelper.maybeHandleExternalFile(rootedPath, env)
            if (env.valuesMissing()) {
                return null
            }
            if (fileType == com.google.devtools.build.lib.skyframe.ExternalFilesHelper.FileType.EXTERNAL_REPO) {
                // do not use syscallCache as files under repositories get generated during the build
                return FileStateValue.create(rootedPath, SyscallCache.NO_CACHE, tsgm.get())
            }
            return FileStateValue.create(rootedPath, syscallCache, tsgm.get())
        } catch (e: NonexistentImmutableExternalFileException) {
            return FileStateValue.NONEXISTENT_FILE_STATE_NODE
        } catch (e: InconsistentFilesystemException) {
            throw FileStateFunctionException(e, Transience.TRANSIENT)
        } catch (e: DetailedIOException) {
            throw FileStateFunctionException(e, e.getTransience())
        } catch (e: IOException) {
            throw FileStateFunctionException(e, Transience.TRANSIENT)
        }
    }

    /**
     * Used to declare all the exception types that can be wrapped in the exception thrown by [ ][FileStateFunction.compute].
     */
    class FileStateFunctionException : SkyFunctionException {
        val isCatastrophic: Boolean

        private constructor(e: InconsistentFilesystemException?, transience: Transience?) : super(e, transience) {
            this.isCatastrophic = true
        }

        private constructor(e: IOException?, transience: Transience?) : super(e, transience) {
            this.isCatastrophic = false
        }
    }
}
