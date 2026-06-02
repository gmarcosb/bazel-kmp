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
//
package com.google.devtools.build.lib.bazel.bzlmod

import com.google.auto.value.AutoValue

/**
 * Wrapper around a dictionary of attribute names and values to facilitate de/serialization (see
 * [AttributeValuesAdapter]).
 * 
 * 
 * Note that all attribute values are stored as Starlark values (that is, [Starlark.valid]
 * -- so [net.starlark.java.eval.StarlarkInt] vs `int`, [ ] vs [List], etc).
 */
@AutoValue
abstract class AttributeValues {
    abstract fun attributes(): net.starlark.java.eval.Dict<String?, Any?>?

    companion object {
        fun create(attribs: net.starlark.java.eval.Dict<String?, Any?>?): AttributeValues {
            return AutoValue_AttributeValues(attribs)
        }
    }
}
