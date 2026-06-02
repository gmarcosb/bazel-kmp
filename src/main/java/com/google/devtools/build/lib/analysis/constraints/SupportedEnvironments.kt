// Copyright 2015 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.analysis.constraints

import com.google.devtools.build.lib.analysis.constraints.EnvironmentCollection
import com.google.devtools.build.lib.analysis.constraints.SupportedEnvironmentsProvider
import com.google.devtools.build.lib.analysis.constraints.SupportedEnvironmentsProvider.RemovedEnvironmentCulprit

/** Standard [SupportedEnvironmentsProvider] implementation.  */
class SupportedEnvironments private constructor(
    staticEnvironments: EnvironmentCollection?,
    refinedEnvironments: EnvironmentCollection?,
    removedEnvironmentCulprits: com.google.common.collect.ImmutableMap<com.google.devtools.build.lib.cmdline.Label?, RemovedEnvironmentCulprit?>
) : SupportedEnvironmentsProvider {
    private val staticEnvironments: EnvironmentCollection?
    private val refinedEnvironments: EnvironmentCollection?
    private val removedEnvironmentCulprits: com.google.common.collect.ImmutableMap<com.google.devtools.build.lib.cmdline.Label?, RemovedEnvironmentCulprit?>

    init {
        this.staticEnvironments = staticEnvironments
        this.refinedEnvironments = refinedEnvironments
        this.removedEnvironmentCulprits = removedEnvironmentCulprits
    }

    override fun getStaticEnvironments(): EnvironmentCollection? {
        return staticEnvironments
    }

    override fun getRefinedEnvironments(): EnvironmentCollection? {
        return refinedEnvironments
    }

    override fun getRemovedEnvironmentCulprit(environment: com.google.devtools.build.lib.cmdline.Label?): RemovedEnvironmentCulprit? {
        return removedEnvironmentCulprits.get(environment)
    }

    companion object {
        val EMPTY: SupportedEnvironments = SupportedEnvironments(
            EnvironmentCollection.Companion.EMPTY,
            EnvironmentCollection.Companion.EMPTY,
            com.google.common.collect.ImmutableMap.of<com.google.devtools.build.lib.cmdline.Label?, RemovedEnvironmentCulprit?>()
        )

        fun create(
            staticEnvironments: EnvironmentCollection,
            refinedEnvironments: EnvironmentCollection,
            removedEnvironmentCulprits: MutableMap<com.google.devtools.build.lib.cmdline.Label?, RemovedEnvironmentCulprit?>
        ): SupportedEnvironments? {
            if (staticEnvironments.isEmpty()
                && refinedEnvironments.isEmpty()
                && removedEnvironmentCulprits.isEmpty()
            ) {
                return EMPTY
            }
            return SupportedEnvironments(
                staticEnvironments,
                refinedEnvironments,
                com.google.common.collect.ImmutableMap.copyOf<com.google.devtools.build.lib.cmdline.Label?, RemovedEnvironmentCulprit?>(
                    removedEnvironmentCulprits
                )
            )
        }
    }
}
