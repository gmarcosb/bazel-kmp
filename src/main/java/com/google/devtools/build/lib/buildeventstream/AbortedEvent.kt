// Copyright 2016 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.buildeventstream

import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.Aborted

/** A [BuildEvent] reporting an event not coming due to the build being aborted.  */
class AbortedEvent private constructor(
    id: BuildEventId?,
    children: MutableCollection<BuildEventId?>?,
    reason: AbortReason?,
    description: String?,
    label: com.google.devtools.build.lib.cmdline.Label?
) : GenericBuildEvent(id, children) {
    private val reason: AbortReason?
    private val description: String?
    private val label: com.google.devtools.build.lib.cmdline.Label?

    constructor(id: BuildEventId?, reason: AbortReason?, description: String?) : this(
        id,
        reason,
        description,  /*label=*/
        null
    )

    constructor(
        id: BuildEventId?,
        reason: AbortReason?,
        description: String?,
        label: com.google.devtools.build.lib.cmdline.Label?
    ) : this(id,  /*children=*/com.google.common.collect.ImmutableList.of<BuildEventId?>(), reason, description, label)

    constructor(
        id: BuildEventId?,
        children: MutableCollection<BuildEventId?>?,
        reason: AbortReason?,
        description: String?
    ) : this(id, children, reason, description,  /*label=*/null)

    init {
        this.reason = reason
        this.description = description
        this.label = label
    }

    fun getLabel(): com.google.devtools.build.lib.cmdline.Label? {
        return label
    }

    override fun asStreamProto(converters: BuildEventContext?): BuildEvent {
        return GenericBuildEvent.Companion.protoChaining(this)
            .setAborted(Aborted.newBuilder().setReason(reason).setDescription(description).build())
            .build()
    }
}
