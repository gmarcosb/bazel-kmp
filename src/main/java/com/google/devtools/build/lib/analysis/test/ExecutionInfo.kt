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
package com.google.devtools.build.lib.analysis.test

import com.google.devtools.build.lib.packages.BuiltinProvider
import com.google.devtools.build.lib.packages.NativeInfo
import com.google.devtools.build.lib.packages.RuleClass
import com.google.devtools.build.lib.starlarkbuildapi.test.ExecutionInfoApi
import com.google.devtools.build.lib.starlarkbuildapi.test.ExecutionInfoApi.ExecutionInfoApiProvider

/**
 * This provider can be implemented by rules which need special environments to run in (especially
 * tests).
 */
@com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable
class ExecutionInfo @kotlin.jvm.JvmOverloads constructor(
    requirements: MutableMap<String?, String?>,
    execGroup: String? = RuleClass.DEFAULT_TEST_RUNNER_EXEC_GROUP_NAME
) : NativeInfo(), ExecutionInfoApi {
    private val executionInfo: com.google.common.collect.ImmutableMap<String?, String?>

    /** Returns the name of the exec group that is used to execute the test.  */
    val execGroup: String

    init {
        this.executionInfo = com.google.common.collect.ImmutableMap.copyOf<String?, String?>(requirements)
        this.execGroup = com.google.common.base.Preconditions.checkNotNull<String>(execGroup)
    }

    val provider: BuiltinProvider<ExecutionInfo?>
        get() = PROVIDER

    /**
     * Returns a map to indicate special execution requirements, such as hardware
     * platforms, etc. Rule tags, such as "requires-XXX", may also be added
     * as keys to the map.
     */
    override fun getExecutionInfo(): com.google.common.collect.ImmutableMap<String?, String?> {
        return executionInfo
    }

    class ExecutionInfoProvider private constructor() :
        BuiltinProvider<ExecutionInfo?>("ExecutionInfo", ExecutionInfo::class.java), ExecutionInfoApiProvider {
        @Throws(net.starlark.java.eval.EvalException::class)
        override fun constructor(
            requirements: net.starlark.java.eval.Dict<*, *>?,  /* <String, String> */
            execGroup: String?
        ): ExecutionInfoApi {
            return ExecutionInfo(
                net.starlark.java.eval.Dict.cast<String?, String?>(
                    requirements,
                    String::class.java,
                    String::class.java,
                    "requirements"
                ), execGroup
            )
        }
    }

    companion object {
        /** Starlark constructor and identifier for ExecutionInfo.  */
        @kotlin.jvm.JvmField
        val PROVIDER: ExecutionInfoProvider = ExecutionInfoProvider()
    }
}
