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
package com.google.devtools.build.lib.skyframe

import com.google.devtools.build.skyframe.SkyKey
import com.google.devtools.build.skyframe.SkyValue

/** Result of [ProcessPackageDirectory.getPackageExistenceAndSubdirDeps].  */
class ProcessPackageDirectoryResult(
    private val packageExists: Boolean,
    childDeps: Iterable<SkyKey?>?,
    additionalValuesToAggregate: com.google.common.collect.ImmutableMap<SkyKey?, SkyValue?>?
) {
    private val childDeps: Iterable<SkyKey?>?
    private val additionalValuesToAggregate: com.google.common.collect.ImmutableMap<SkyKey?, SkyValue?>?

    /** `childDeps` and `additionalValuesToAggregate` must be disjoint.  */
    init {
        this.childDeps = childDeps
        this.additionalValuesToAggregate = additionalValuesToAggregate
    }

    fun packageExists(): Boolean {
        return packageExists
    }

    fun getChildDeps(): Iterable<SkyKey?>? {
        return childDeps
    }

    fun getAdditionalValuesToAggregate(): com.google.common.collect.ImmutableMap<SkyKey?, SkyValue?>? {
        return additionalValuesToAggregate
    }

    companion object {
        val EMPTY_RESULT: ProcessPackageDirectoryResult = ProcessPackageDirectoryResult(
            false,
            com.google.common.collect.ImmutableList.of<SkyKey?>(),
            com.google.common.collect.ImmutableMap.of<SkyKey?, SkyValue?>()
        )
    }
}
