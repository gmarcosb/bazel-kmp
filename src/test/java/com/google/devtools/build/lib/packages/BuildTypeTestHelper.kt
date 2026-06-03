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
package com.google.devtools.build.lib.packages

/** Utils for testing [Type], [Types], and [BuildType].  */ // Must live in the same package as BuildType to access non-public fields.
object BuildTypeTestHelper {
    /**
     * Returns all build types defined in [Type], [Types], and [BuildType].
     * 
     * @param publicOnly if true, only returns public types; otherwise, returns both public and
     * internal package-private ones.
     */
    @Throws(java.lang.IllegalAccessException::class)
    fun getAllBuildTypes(publicOnly: Boolean): com.google.common.collect.ImmutableList<Type<*>?> {
        val builder: com.google.common.collect.ImmutableList.Builder<Type<*>?> =
            com.google.common.collect.ImmutableList.builder<Type<*>?>()
        collectBuildTypeStaticFields(builder, Type::class.java, publicOnly)
        collectBuildTypeStaticFields(builder, Types::class.java, publicOnly)
        collectBuildTypeStaticFields(builder, BuildType::class.java, publicOnly)
        return builder.build()
    }

    @Throws(java.lang.IllegalAccessException::class)
    private fun collectBuildTypeStaticFields(
        builder: com.google.common.collect.ImmutableList.Builder<Type<*>?>,
        clazz: java.lang.Class<*>,
        publicOnly: Boolean
    ) {
        for (field in clazz.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(field.getModifiers())
                && (java.lang.reflect.Modifier.isPublic(field.getModifiers()) || !publicOnly)
                && Type::class.java.isAssignableFrom(field.getType())
            ) {
                builder.add(field.get(null) as Type<*>?)
            }
        }
    }
}
