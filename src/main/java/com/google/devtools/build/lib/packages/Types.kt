// Copyright 2014 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.packages

import com.google.devtools.build.lib.skyframe.serialization.autocodec.SerializationConstant

/**
 * Constants for [Type]s.
 * 
 * 
 * These constants are in a separate class from [Type] to break a class initialization
 * cycle, and prevent possible deadlocks.
 */
object Types {
    /** The type of a list of strings.  */
    @kotlin.jvm.JvmField
    @SerializationConstant
    val STRING_LIST: com.google.devtools.build.lib.packages.Type.ListType<String?> =
        com.google.devtools.build.lib.packages.Type.ListType.Companion.create<String?>(com.google.devtools.build.lib.packages.Type.Companion.STRING)

    /** The type of a set of strings.  */
    @kotlin.jvm.JvmField
    @SerializationConstant
    val STRING_SET: com.google.devtools.build.lib.packages.Type.SetType<String?> =
        com.google.devtools.build.lib.packages.Type.SetType.Companion.create<String?>(com.google.devtools.build.lib.packages.Type.Companion.STRING)

    /** The type of a list of signed 32-bit Starlark integer values.  */
    @kotlin.jvm.JvmField
    @SerializationConstant
    val INTEGER_LIST: com.google.devtools.build.lib.packages.Type.ListType<net.starlark.java.eval.StarlarkInt?> =
        com.google.devtools.build.lib.packages.Type.ListType.Companion.create<net.starlark.java.eval.StarlarkInt?>(com.google.devtools.build.lib.packages.Type.Companion.INTEGER)

    /** The type of a dictionary of [strings][Type.STRING].  */
    @kotlin.jvm.JvmField
    @SerializationConstant
    val STRING_DICT: com.google.devtools.build.lib.packages.Type.DictType<String?, String?> =
        com.google.devtools.build.lib.packages.Type.DictType.Companion.create<String?, String?>(
            com.google.devtools.build.lib.packages.Type.Companion.STRING,
            com.google.devtools.build.lib.packages.Type.Companion.STRING
        )

    /** The type of a dictionary of [label lists][.STRING_LIST].  */
    @kotlin.jvm.JvmField
    @SerializationConstant
    val STRING_LIST_DICT: com.google.devtools.build.lib.packages.Type.DictType<String?, MutableList<String?>?> =
        com.google.devtools.build.lib.packages.Type.DictType.Companion.create<String?, MutableList<String?>?>(
            com.google.devtools.build.lib.packages.Type.Companion.STRING,
            com.google.devtools.build.lib.packages.Types.STRING_LIST
        )
}
