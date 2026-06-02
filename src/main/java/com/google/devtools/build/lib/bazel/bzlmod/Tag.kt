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
//
package com.google.devtools.build.lib.bazel.bzlmod

import com.google.auto.value.AutoValue
import com.ryanharter.auto.value.gson.GenerateTypeAdapter

/**
 * Represents a module extension tag, which is a piece of data following a specified attribute
 * schema that can be consumed by a module extension implementation function. The attribute schema
 * is defined by the [TagClass], and checked at module extension resolution time (i.e.
 * *not* when the tag is created, which is during module discovery).
 */
@AutoValue
@GenerateTypeAdapter
abstract class Tag {
    abstract val tagName: String?

    /** All keyword arguments supplied to the tag instance.  */
    abstract val attributeValues: com.google.devtools.build.lib.bazel.bzlmod.AttributeValues?

    /** Whether this tag was created using a proxy created with dev_dependency = True.  */
    abstract val isDevDependency: Boolean

    /** The source location in the module file where this tag was created.  */
    abstract val location: net.starlark.java.syntax.Location?

    abstract fun toBuilder(): Builder?

    /**
     * Returns a new tag with all information removed that does not influence the evaluation of the
     * extension defining the tag.
     */
    fun trimForEvaluation(): Tag? {
        // We start with the full usage and selectively remove information that does not influence the
        // evaluation of the extension. Compared to explicitly copying over the parts that do, this
        // preserves correctness in case new fields are added without updating this code.
        return toBuilder()!! // Locations are only used for error reporting and thus don't influence whether the
            // evaluation of the extension is successful and what its result is in case of success.
            .setLocation(net.starlark.java.syntax.Location.BUILTIN)!!
            .build()
    }

    /** Builder for [Tag].  */
    @AutoValue.Builder
    abstract class Builder {
        abstract fun setTagName(value: String?): Builder?

        abstract fun setAttributeValues(value: com.google.devtools.build.lib.bazel.bzlmod.AttributeValues?): Builder?

        abstract fun setDevDependency(value: Boolean): Builder?

        abstract fun setLocation(value: net.starlark.java.syntax.Location?): Builder?

        abstract fun build(): Tag?
    }

    companion object {
        @kotlin.jvm.JvmStatic
        fun builder(): Builder {
            return Builder()
        }
    }
}
