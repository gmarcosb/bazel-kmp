// Copyright 2023 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.analysis.test

import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos

/** This event may be raised while a test action is executing to report info about its execution.  */
@com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable
class TestProgress(
    /** The label of the target for the action.  */
    private val label: String,
    configId: ConfigurationId,
    run: Int,
    shard: Int,
    attempt: Int,
    opaqueCount: Int,
    uri: String
) : BuildEvent {
    /** The configuration under which the action is running.  */
    private val configId: BuildEventId.ConfigurationId

    /** The run number of the test action (e.g. for runs_per_test > 1).  */
    private val run: Int

    /** For sharded tests, the shard number of the test action.  */
    private val shard: Int

    /** The execution attempt number which may increase due to retries.  */
    private val attempt: Int

    /** A count which may be incremented to differentiate events.  */
    private val opaqueCount: Int

    /** Identifies a resource that can provide info about the active test run.  */
    private val uri: String

    init {
        this.configId = configId
        this.run = run
        this.shard = shard
        this.attempt = attempt
        this.opaqueCount = opaqueCount
        this.uri = uri
    }

    val eventId: BuildEventId?
        get() = BuildEventIdUtil.testProgressId(label, configId, run, shard, attempt, opaqueCount)

    val childrenEvents: com.google.common.collect.ImmutableList<BuildEventId?>
        get() = com.google.common.collect.ImmutableList.of<BuildEventId?>()

    override fun asStreamProto(converters: BuildEventContext?): BuildEvent {
        return GenericBuildEvent.protoChaining(this).setTestProgress(asTestResult()).build()
    }

    override fun hashCode(): Int {
        return com.google.common.base.Objects.hashCode(label, configId, run, shard, attempt, opaqueCount, uri)
    }

    override fun equals(`object`: Any?): Boolean {
        if (`object` !is TestProgress) {
            return false
        }
        return label == `object`.label
                && configId.equals(`object`.configId)
                && run == `object`.run && shard == `object`.shard && attempt == `object`.attempt && opaqueCount == `object`.opaqueCount && uri == `object`.uri
    }

    override fun toString(): String {
        return com.google.common.base.MoreObjects.toStringHelper(this)
            .add("label", label)
            .add("configId", configId)
            .add("run", run)
            .add("shard", shard)
            .add("attempt", attempt)
            .add("opaqueCount", opaqueCount)
            .add("uri", uri)
            .toString()
    }

    private fun asTestResult(): TestProgress {
        return BuildEventStreamProtos.TestProgress.newBuilder().setUri(uri).build()
    }
}
