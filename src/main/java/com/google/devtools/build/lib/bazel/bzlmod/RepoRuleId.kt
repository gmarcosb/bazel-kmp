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
//
package com.google.devtools.build.lib.bazel.bzlmod

import com.google.auto.value.AutoBuilder
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec

/**
 * Identifies a repo rule.
 * 
 * @param bzlFileLabel The label pointing to the .bzl file defining the repo rule.
 * @param ruleName The name of the repo rule.
 */
@AutoCodec
class RepoRuleId(bzlFileLabel: com.google.devtools.build.lib.cmdline.Label?, ruleName: String?) {
    override fun toString(): String {
        return bzlFileLabel.getUnambiguousCanonicalForm() + "%" + ruleName
    }

    fun toBuilder(): Builder {
        return AutoBuilder_RepoRuleId_Builder(this)
    }

    /** Builder type for [RepoRuleId].  */
    @AutoBuilder
    abstract class Builder {
        abstract fun bzlFileLabel(value: com.google.devtools.build.lib.cmdline.Label?): Builder?

        abstract fun bzlFileLabel(): com.google.devtools.build.lib.cmdline.Label?

        abstract fun ruleName(value: String?): Builder?

        abstract fun ruleName(): java.util.Optional<String?>?

        val isRuleNameSet: Boolean
            get() = ruleName().isPresent()

        abstract fun build(): RepoRuleId?
    }

    val bzlFileLabel: com.google.devtools.build.lib.cmdline.Label?
    val ruleName: String?

    init {
        this.bzlFileLabel = bzlFileLabel
        this.ruleName = ruleName
    }

    companion object {
        fun builder(): Builder {
            return AutoBuilder_RepoRuleId_Builder()
        }
    }
}
