// Copyright 2021 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.bazel.bzlmod

import com.google.devtools.build.lib.starlarkbuildapi.repository.RepositoryModuleApi.TagClassApi

/**
 * Represents a tag class, which is a "class" of [Tag]s that share the same attribute schema.
 * 
 * @param attributes The list of attributes of this tag class.
 * @param doc Documentation about this tag class.
 * @param attributeIndices A mapping from the * public * name of an attribute to the position
 * of said attribute in [.getAttributes].
 */
class TagClass(
    attributes: com.google.common.collect.ImmutableList<com.google.devtools.build.lib.packages.Attribute?>?,
    doc: java.util.Optional<String?>?,
    attributeIndices: com.google.common.collect.ImmutableMap<String?, Int?>?
) : TagClassApi {
    val attributes: com.google.common.collect.ImmutableList<com.google.devtools.build.lib.packages.Attribute?>?
    val doc: java.util.Optional<String?>?
    val attributeIndices: com.google.common.collect.ImmutableMap<String?, Int?>?

    init {
        this.attributeIndices = attributeIndices
        this.doc = doc
        this.attributes = attributes
        java.util.Objects.requireNonNull<com.google.common.collect.ImmutableList<com.google.devtools.build.lib.packages.Attribute?>?>(
            attributes,
            "attributes"
        )
        java.util.Objects.requireNonNull<java.util.Optional<String?>?>(doc, "doc")
        java.util.Objects.requireNonNull<com.google.common.collect.ImmutableMap<String?, Int?>?>(
            attributeIndices,
            "attributeIndices"
        )
    }

    companion object {
        fun create(
            attributes: com.google.common.collect.ImmutableList<com.google.devtools.build.lib.packages.Attribute?>,
            doc: java.util.Optional<String?>?
        ): TagClass {
            val attributeIndicesBuilder: com.google.common.collect.ImmutableMap.Builder<String?, Int?> =
                com.google.common.collect.ImmutableMap.builderWithExpectedSize<String?, Int?>(attributes.size())
            for (i in attributes.indices) {
                attributeIndicesBuilder.put(attributes.get(i).getPublicName(), i)
            }
            return TagClass(attributes, doc, attributeIndicesBuilder.buildOrThrow())
        }
    }
}
