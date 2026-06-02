// Copyright 2025 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.runtime.commands.info

import com.google.devtools.build.lib.runtime.CommandEnvironment

/** Handles how [InfoItem]s are outputted.  */
interface InfoItemHandler : AutoCloseable {
    /** Stores [InfoItem] information.  */
    @Throws(AbruptExitException::class, InterruptedException::class, IOException::class)
    fun addInfoItem(key: String?, value: ByteArray?)

    /** Flushes any internal state.  */
    @Throws(IOException::class)
    override fun close()

    /** Defines the way to output [InfoItem] information.  */
    enum class InfoItemOutputType {
        /**
         * Info information is directly printed to the console. [StdoutInfoItemHandler] is created
         * if this type is passed in.
         */
        STDOUT,

        /**
         * Info information is packed in response extensions for downstream service processing. [ ] is created if this type is passed in.
         */
        RESPONSE_PROTO
    }

    /** Contains method to create the correct type of [InfoItemHandler].  */
    interface InfoItemHandlerFactory {
        fun create(
            env: CommandEnvironment?, infoItemOutputType: InfoItemOutputType?, printKeys: Boolean
        ): InfoItemHandler?
    }

    /**
     * Implementation of [InfoItemHandlerFactory] that creates [InfoItemHandler] instances
     * based on the provided [InfoItemOutputType].
     */
    class InfoItemHandlerFactoryImpl : InfoItemHandlerFactory {
        override fun create(
            env: CommandEnvironment, infoItemOutputType: InfoItemOutputType, printKeys: Boolean
        ): InfoItemHandler {
            return when (infoItemOutputType) {
                InfoItemOutputType.STDOUT -> StdoutInfoItemHandler(env.getReporterOutErr(), printKeys)
                InfoItemOutputType.RESPONSE_PROTO -> RemoteRequestedInfoItemHandler(env, printKeys)
            }
        }
    }
}
