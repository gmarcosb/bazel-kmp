// Copyright 2016 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.skyframe

import com.google.devtools.build.lib.skyframe.EnvironmentVariableValue
import com.google.devtools.build.lib.skyframe.SkyFunctions
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec
import com.google.devtools.build.skyframe.AbstractSkyKey
import com.google.devtools.build.skyframe.SkyFunction
import com.google.devtools.build.skyframe.SkyFunctionName
import com.google.devtools.build.skyframe.SkyKey
import com.google.devtools.build.skyframe.SkyKey.SkyKeyInterner
import com.google.devtools.build.skyframe.SkyValue
import java.util.concurrent.atomic.AtomicReference

/** The Skyframe function that generates values for variables of the client environment.  */
class ClientEnvironmentFunction(clientEnv: AtomicReference<MutableMap<String?, String?>?>) : SkyFunction {
    /** The Skyframe key for the client environment function.  */
    @com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization
    @AutoCodec
    class Key private constructor(arg: String?) : AbstractSkyKey<String?>(arg) {
        override fun functionName(): SkyFunctionName {
            return SkyFunctions.CLIENT_ENVIRONMENT_VARIABLE
        }

        val skyKeyInterner: SkyKeyInterner<Key?>
            get() = com.google.devtools.build.lib.skyframe.ClientEnvironmentFunction.Key.Companion.interner

        companion object {
            private val interner: SkyKeyInterner<Key?> = SkyKey.newInterner<Key?>()

            private fun create(arg: String?): Key {
                return com.google.devtools.build.lib.skyframe.ClientEnvironmentFunction.Key.Companion.interner.intern(
                    com.google.devtools.build.lib.skyframe.ClientEnvironmentFunction.Key(arg)
                )
            }

            @com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization
            @AutoCodec.Interner
            fun intern(key: Key?): Key {
                return com.google.devtools.build.lib.skyframe.ClientEnvironmentFunction.Key.Companion.interner.intern(
                    key
                )
            }
        }
    }

    private val clientEnv: AtomicReference<MutableMap<String?, String?>?>

    init {
        this.clientEnv = clientEnv
    }

    override fun compute(key: SkyKey, env: SkyFunction.Environment?): SkyValue? {
        return EnvironmentVariableValue(clientEnv.get().get(key.argument() as String?))
    }

    companion object {
        @kotlin.jvm.JvmStatic
        fun key(keyString: String?): Key {
            return com.google.devtools.build.lib.skyframe.ClientEnvironmentFunction.Key.Companion.create(keyString)
        }
    }
}
