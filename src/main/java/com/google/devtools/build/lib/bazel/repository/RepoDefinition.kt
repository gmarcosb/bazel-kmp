// Copyright 2025 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.bazel.repository

import com.google.devtools.build.lib.bazel.bzlmod.AttributeValues

/**
 * A fully-loaded repo definition, ready to be fetched. This class doubles as a Starlark value that
 * provides its own attribute struct.
 */
@AutoCodec
class RepoDefinition(repoRule: RepoRule?, attrValues: AttributeValues?, name: String?, originalName: String?) :
    net.starlark.java.eval.Structure {
    val isImmutable: Boolean
        get() = true

    override fun getValue(field: String): Any? {
        if (field == "name") {
            // Special case: `rctx.attr.name` can be used in place of `rctx.name`.
            return name
        }
        val value: Any? = attrValues.attributes().get(field)
        if (value != null) {
            return value
        }
        val index: Int? = repoRule.attributeIndices.get(field)
        if (index == null) {
            return null
        }
        return com.google.devtools.build.lib.packages.Attribute.valueToStarlark(
            repoRule.attributes.get(index).getDefaultValueUnchecked()
        )
    }

    val fieldNames: com.google.common.collect.ImmutableSet<String?>
        get() = com.google.common.collect.Sets.union<String?>(
            repoRule.attributeIndices.keySet(),
            com.google.common.collect.ImmutableSet.of<String?>("name")
        )
            .immutableCopy()

    override fun getErrorMessageForUnknownField(field: String): String {
        return "unknown attribute " + field + net.starlark.java.spelling.SpellChecker.didYouMean(field, this.fieldNames)
    }

    val repoRule: RepoRule?
    val attrValues: AttributeValues?
    val name: String?
    val originalName: String?

    init {
        this.repoRule = repoRule
        this.attrValues = attrValues
        this.name = name
        this.originalName = originalName
    }
}
