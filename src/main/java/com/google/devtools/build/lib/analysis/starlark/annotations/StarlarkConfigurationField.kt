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
package com.google.devtools.build.lib.analysis.starlark.annotations

/**
 * A marker interface for Java methods of Starlark-exposed configuration fragments which denote
 * Starlark "configuration fields": late-bound attribute defaults that depend on configuration.
 * 
 * 
 * Methods annotated with this annotation have a few constraints:
 * 
 * 
 *  * The annotated method must be on a configuration fragment exposed to Starlark.
 *  * The method must have return type Label.
 *  * The method must be public.
 *  * The method must have zero arguments.
 *  * The method must not throw exceptions.
 * 
 */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY_GETTER, AnnotationTarget.PROPERTY_SETTER)
@Retention(AnnotationRetention.RUNTIME)
annotation class StarlarkConfigurationField(
    /** Name of the configuration field, as exposed to Starlark.  */
    val name: String,
    /**
     * The default label associated with this field, corresponding to the value of this configuration
     * field with default command line flags.
     * 
     * 
     * If the default label is under the tools repository, omit the tools repository prefix
     * from this default, but set [.defaultInToolRepository] to true.
     */
    val defaultLabel: String = "",
    /**
     * Whether the default label as defined in [.defaultLabel] should be prefixed with
     * the tools repository.
     */
    val defaultInToolRepository: Boolean = false,
    /**
     * The documentation text in Starlark. It can contain HTML tags for special formatting.
     * 
     * 
     * It is allowed to be empty only if [.documented] is false.
     */
    val doc: String = "",
    /**
     * If true, the function will appear in the Starlark documentation. Set this to false if the
     * function is experimental or an overloading and doesn't need to be documented.
     */
    val documented: Boolean = true
)
