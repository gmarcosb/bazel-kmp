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
package com.google.devtools.build.lib.packages

import com.google.devtools.build.lib.packages.AspectClass
import com.google.devtools.build.lib.packages.AspectParameters
import com.google.devtools.build.lib.starlarkbuildapi.StarlarkAspectApi

/** Represents an aspect which can be attached to a Starlark-defined rule attribute.  */
interface StarlarkAspect : StarlarkAspectApi {
    /** Returns the aspect class for this aspect.  */
    fun getAspectClass(): AspectClass?

    /** Returns a set of the names of parameters required to create this aspect.  */
    fun getParamAttributes(): com.google.common.collect.ImmutableSet<String?>?

    /** Returns the name of this aspect.  */
    fun getName(): String?

    /** Returns a function to extract the aspect parameters values from its base rule.  */
    fun getDefaultParametersExtractor(): com.google.common.base.Function<com.google.devtools.build.lib.packages.Rule?, AspectParameters?>?
}
