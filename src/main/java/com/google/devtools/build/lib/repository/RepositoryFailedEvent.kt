// Copyright 2019 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.cmdline.LabelConstants.EXTERNAL_PACKAGE_IDENTIFIER

/**
 * Event indicating that a failure is related to a given external repository; this is in particular
 * the case, if fetching that repository failed.
 */
class RepositoryFailedEvent(repo: RepositoryName, message: String?) : BuildEvent {
    private val repo: RepositoryName
    private val message: String?

    init {
        this.repo = repo
        this.message = message
    }

    fun getRepo(): RepositoryName {
        return repo
    }

    val eventId: BuildEventId
        get() {
            val strippedRepoName: String? = repo.name
            try {
                val label: Label? = Label.create(EXTERNAL_PACKAGE_IDENTIFIER, strippedRepoName)
                return BuildEventIdUtil.unconfiguredLabelId(label)
            } catch (e: LabelSyntaxException) {
                // As the repository name was accepted earlier, the label construction really shouldn't fail.
                // In any case, return something still referring to the repository.
                return BuildEventIdUtil.unknownBuildEventId(
                    EXTERNAL_PACKAGE_IDENTIFIER + ":" + strippedRepoName
                )
            }
        }

    val childrenEvents: MutableCollection<BuildEventId>
        get() = com.google.common.collect.ImmutableList.of<BuildEventId?>()

    public override fun asStreamProto(context: BuildEventContext?): BuildEventStreamProtos.BuildEvent {
        return GenericBuildEvent.protoChaining(this)
            .setAborted(
                BuildEventStreamProtos.Aborted.newBuilder()
                    .setReason(BuildEventStreamProtos.Aborted.AbortReason.LOADING_FAILURE)
                    .setDescription(message)
                    .build()
            )
            .build()
    }
}
