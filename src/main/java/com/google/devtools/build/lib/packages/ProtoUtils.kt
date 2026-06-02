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

import com.google.devtools.build.lib.query2.proto.proto2api.Build.Attribute.Discriminator

/** Shared code used in proto buffer output for rules and rule classes.  */
object ProtoUtils {
    /** This map contains all attribute types that are recognized by the protocol output formatter.  */
    private val TYPE_MAP: com.google.common.collect.ImmutableMap<com.google.devtools.build.lib.packages.Type<*>?, Discriminator?> =
        com.google.common.collect.ImmutableMap.Builder<com.google.devtools.build.lib.packages.Type<*>?, Discriminator?>()
            .put(com.google.devtools.build.lib.packages.Type.Companion.INTEGER, Discriminator.INTEGER)
            .put(
                BuildType.LABEL,
                Discriminator.LABEL
            ) // NODEP_LABEL attributes are not really strings. This is implemented
            // this way for the sake of backward compatibility.
            .put(BuildType.NODEP_LABEL, Discriminator.STRING)
            .put(BuildType.LABEL_LIST, Discriminator.LABEL_LIST)
            .put(BuildType.GENQUERY_SCOPE_TYPE, Discriminator.LABEL)
            .put(BuildType.GENQUERY_SCOPE_TYPE_LIST, Discriminator.LABEL_LIST)
            .put(BuildType.NODEP_LABEL_LIST, Discriminator.STRING_LIST)
            .put(BuildType.DORMANT_LABEL, Discriminator.LABEL_LIST)
            .put(BuildType.DORMANT_LABEL_LIST, Discriminator.STRING_LIST)
            .put(com.google.devtools.build.lib.packages.Type.Companion.STRING, Discriminator.STRING)
            .put(com.google.devtools.build.lib.packages.Type.Companion.STRING_NO_INTERN, Discriminator.STRING)
            .put(com.google.devtools.build.lib.packages.Types.STRING_LIST, Discriminator.STRING_LIST)
            .put(com.google.devtools.build.lib.packages.Types.STRING_SET, Discriminator.DISTRIBUTION_SET)
            .put(BuildType.OUTPUT, Discriminator.OUTPUT)
            .put(BuildType.OUTPUT_LIST, Discriminator.OUTPUT_LIST)
            .put(BuildType.LICENSE, Discriminator.LICENSE)
            .put(com.google.devtools.build.lib.packages.Types.STRING_DICT, Discriminator.STRING_DICT)
            .put(BuildType.LABEL_DICT_UNARY, Discriminator.LABEL_DICT_UNARY)
            .put(com.google.devtools.build.lib.packages.Types.STRING_LIST_DICT, Discriminator.STRING_LIST_DICT)
            .put(BuildType.LABEL_LIST_DICT, Discriminator.LABEL_LIST_DICT)
            .put(com.google.devtools.build.lib.packages.Type.Companion.BOOLEAN, Discriminator.BOOLEAN)
            .put(BuildType.TRISTATE, Discriminator.TRISTATE)
            .put(com.google.devtools.build.lib.packages.Types.INTEGER_LIST, Discriminator.INTEGER_LIST)
            .put(BuildType.LABEL_KEYED_STRING_DICT, Discriminator.LABEL_KEYED_STRING_DICT)
            .build()

    /** Returns the [Discriminator] value corresponding to the provided [Type].  */
    fun getDiscriminatorFromType(type: com.google.devtools.build.lib.packages.Type<*>?): Discriminator? {
        return com.google.common.base.Preconditions.checkNotNull<Discriminator?>(
            TYPE_MAP.get(type),
            "No discriminator found for %s",
            type
        )
    }
}
