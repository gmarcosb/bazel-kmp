// Copyright 2024 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.vfs

import com.google.devtools.build.lib.server.FailureDetails.FailureDetail

/**
 * An [IOException] that includes [DetailedExitCode] and [Transience]. Currently
 * only used for [Filesystem] exceptions.
 */
class DetailedIOException(
    message: String?,
    cause: IOException?,
    filesystemCode: Filesystem.Code?,
    transience: Transience?
) : IOException(message, cause), DetailedException {
    private val detailedExitCode: DetailedExitCode?
    private val transience: Transience?

    init {
        this.detailedExitCode =
            DetailedExitCode.of(
                FailureDetail.newBuilder()
                    .setMessage(message)
                    .setFilesystem(Filesystem.newBuilder().setCode(filesystemCode))
                    .build()
            )
        this.transience = transience
    }

    public override fun getDetailedExitCode(): DetailedExitCode? {
        return detailedExitCode
    }

    fun getTransience(): Transience? {
        return transience
    }
}
