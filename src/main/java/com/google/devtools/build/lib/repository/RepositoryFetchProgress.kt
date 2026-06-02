// Copyright 2022 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.repository

import com.google.devtools.build.lib.cmdline.RepositoryName

/** Reports the prgoress of a repository fetch.  */
class RepositoryFetchProgress private constructor(repoName: RepositoryName?, finished: Boolean, message: String?) :
    FetchProgress {
    private val repoName: RepositoryName?
    val isFinished: Boolean
    val progress: String?

    init {
        this.repoName = repoName
        this.isFinished = finished
        this.progress = message
    }

    val resourceIdentifier: String
        get() = repositoryFetchContextString(repoName)

    companion object {
        /** Returns the unique identifying string for a repository fetching event.  */
        fun repositoryFetchContextString(repoName: RepositoryName?): String {
            return "repository " + repoName
        }

        fun ongoing(repoName: RepositoryName?, message: String?): RepositoryFetchProgress {
            return RepositoryFetchProgress(repoName,  /*finished=*/false, message)
        }

        fun finished(repoName: RepositoryName?): RepositoryFetchProgress {
            return RepositoryFetchProgress(repoName,  /*finished=*/true, "finished.")
        }
    }
}
