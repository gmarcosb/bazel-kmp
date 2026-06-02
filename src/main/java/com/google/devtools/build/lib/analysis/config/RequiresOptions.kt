// Copyright 2020 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.analysis.config

import com.google.devtools.build.lib.analysis.config.FragmentOptions
import kotlin.reflect.KClass

/**
 * Interface for a [Fragment] object to declare which [FragmentOptions] it needs for
 * construction.
 * 
 * 
 * Blaze instantiates [Fragment] with a [BuildOptions] that only contains the [ ] specified here.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class RequiresOptions(
    /** The options required by the annotated fragment. By default, fragments require no options.  */
    val options: Array<KClass<out FragmentOptions?>> = [],
    /** Whether the annotated fragment requires access to starlark options.  */
    val starlark: Boolean = false
)
