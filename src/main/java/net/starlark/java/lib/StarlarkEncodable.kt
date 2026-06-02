// Copyright 2026 The Bazel Authors. All rights reserved.
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
package net.starlark.java.lib

/** An interface allowing Starlark values to define their own structured text encoding.  */
interface StarlarkEncodable {
    /**
     * Returns a value which represents this object and which will be encoded by [ ][net.starlark.java.lib.json.Json.encode] and [ ].
     * 
     * 
     * The returned value must be one of the following:
     * 
     * 
     *  * [net.starlark.java.eval.Starlark.NONE]
     *  * a [Boolean], [String], [net.starlark.java.eval.StarlarkInt], or [       ]
     *  * a [java.util.Map] (for example, a [net.starlark.java.eval.Dict]). For
     * compatibility with all encoders, the keys must be strings, and the values must be
     * encodable scalars or structs.
     *  * a [net.starlark.java.eval.StarlarkIterable] of encodable elements. For
     * compatibility with all encoders, the elements must be encodable scalars or structs.
     *  * a [net.starlark.java.eval.Structure] with encodable field values.
     * 
     * 
     * 
     * Returning a `Structure` is recommended, unless there is a strong reason otherwise.
     */
    fun objectForEncoding(semantics: net.starlark.java.eval.StarlarkSemantics?): Any?
}
