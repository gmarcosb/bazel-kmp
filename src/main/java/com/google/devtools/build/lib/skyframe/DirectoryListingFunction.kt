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

import com.google.devtools.build.lib.actions.FileValue

/**
 * A [SkyFunction] for [DirectoryListingValue]s.
 */
class DirectoryListingFunction : SkyFunction {
    @Throws(DirectoryListingFunctionException::class, java.lang.InterruptedException::class)
    override fun compute(skyKey: SkyKey, env: SkyFunction.Environment): SkyValue? {
        val dirRootedPath: RootedPath = skyKey.argument() as RootedPath

        val dirFileValue: FileValue? = env.getValue(FileValue.key(dirRootedPath)) as FileValue?
        if (dirFileValue == null) {
            return null
        }

        val realDirRootedPath: RootedPath? = dirFileValue.realRootedPath(dirRootedPath)
        if (!dirFileValue.isDirectory()) {
            // Recall that the directory is assumed to exist (see DirectoryListingValue#key).
            throw DirectoryListingFunctionException(
                InconsistentFilesystemException(
                    (dirRootedPath.asPath()
                        .toString() + " is no longer an existing directory. Did you delete it during "
                            + "the build?")
                )
            )
        }

        val directoryListingStateValue: DirectoryListingStateValue? =
            env.getValue(
                DirectoryListingStateValue.Companion.key(
                    realDirRootedPath
                )
            ) as DirectoryListingStateValue?
        if (directoryListingStateValue == null) {
            return null
        }

        return DirectoryListingValue.Companion.value(dirRootedPath, dirFileValue, directoryListingStateValue)
    }

    /**
     * Used to declare all the exception types that can be wrapped in the exception thrown by
     * [DirectoryListingFunction.compute].
     */
    private class DirectoryListingFunctionException(e: InconsistentFilesystemException?) :
        SkyFunctionException(e, Transience.TRANSIENT)
}
