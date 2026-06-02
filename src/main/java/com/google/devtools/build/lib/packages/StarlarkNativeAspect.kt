// Copyright 2015 The Bazel Authors. All rights reserved.
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
import com.google.devtools.build.lib.packages.NativeAspectClass
import com.google.devtools.build.lib.packages.StarlarkAspect
import com.google.devtools.build.lib.skyframe.serialization.autocodec.SerializationConstant

/** A natively-defined aspect that is may be referenced by Starlark attribute definitions.  */
abstract class StarlarkNativeAspect : NativeAspectClass(), StarlarkAspect {
    override fun repr(printer: net.starlark.java.eval.Printer, semantics: net.starlark.java.eval.StarlarkSemantics?) {
        printer.append("<native aspect>")
    }

    override fun getAspectClass(): AspectClass {
        return this
    }

    override fun getParamAttributes(): com.google.common.collect.ImmutableSet<String?>? {
        return com.google.common.collect.ImmutableSet.of<String?>()
    }

    override fun getDefaultParametersExtractor(): com.google.common.base.Function<com.google.devtools.build.lib.packages.Rule?, AspectParameters?> {
        return EMPTY_FUNCTION
    }

    companion object {
        @SerializationConstant
        @com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization
        val EMPTY_FUNCTION: com.google.common.base.Function<com.google.devtools.build.lib.packages.Rule?, AspectParameters?> =
            com.google.common.base.Function { input: com.google.devtools.build.lib.packages.Rule? -> AspectParameters.Companion.EMPTY }
    }
}
