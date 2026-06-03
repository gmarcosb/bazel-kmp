// Copyright 2021 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.rules.java

import com.google.devtools.build.lib.packages.StarlarkInfo

/** Provides information about C++ libraries to be linked into Java targets.  */
@com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable
@AutoCodec
class JavaCcInfoProvider(ccInfo: CcInfo?) : JavaInfoInternalProvider {
    val ccInfo: CcInfo?

    init {
        this.ccInfo = ccInfo
        java.util.Objects.requireNonNull<Any?>(ccInfo, "ccInfo")
    }

    companion object {
        // TODO(b/183579145): Replace CcInfo with only linking information.
        fun create(ccInfo: CcInfo?): JavaCcInfoProvider {
            return JavaCcInfoProvider(ccInfo)
        }

        @Throws(net.starlark.java.eval.EvalException::class)
        fun fromStarlarkJavaInfo(javaInfo: StructImpl): JavaCcInfoProvider? {
            val ccInfo: StarlarkInfo? = javaInfo.getValue("cc_link_params_info", StarlarkInfo::class.java)
            if (ccInfo == null) {
                return null
            }
            return create(CcInfo.wrap(ccInfo))
        }
    }
}
