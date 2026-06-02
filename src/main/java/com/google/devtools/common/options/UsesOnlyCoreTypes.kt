// Copyright 2017 The Bazel Authors. All rights reserved.
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
package com.google.devtools.common.options

/**
 * Applied to an [OptionsBase] subclass to indicate that all of its options fields have types
 * chosen from [.coreTypes]. Any subclasses of the class to which it's applied must also
 * satisfy the same property.
 * 
 * 
 * Options classes with this annotation are serializable and deeply immutable, except that the
 * fields of the options class can be reassigned (although this is bad practice).
 * 
 * 
 * Note that [Option.allowMultiple] is not allowed for options in classes with this
 * annotation, since their type is [List].
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@java.lang.annotation.Inherited
annotation class UsesOnlyCoreTypes {
    companion object {
        /**
         * These are the core options field types. They all have default converters, are deeply immutable,
         * and are serializable.
         * 
         * Lists are not considered core types, so [Option.allowMultiple] options are not permitted.
         */
        val CORE_TYPES: com.google.common.collect.ImmutableList<java.lang.Class<*>?> =
            com.google.common.collect.ImmutableList.of<java.lang.Class<*>?>( // 1:1 correspondence with Converters.DEFAULT_CONVERTERS.
                String::class.java,
                Int::class.javaPrimitiveType,
                Long::class.javaPrimitiveType,
                Double::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
                com.google.devtools.common.options.TriState::class.java,
                java.lang.Void::class.java,
                java.time.Duration::class.java
            )
    }
}
