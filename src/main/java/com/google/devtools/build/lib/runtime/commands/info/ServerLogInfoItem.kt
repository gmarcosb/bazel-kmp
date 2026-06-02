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
package com.google.devtools.build.lib.runtime.commands.info

import com.google.common.base.Preconditions
import com.google.common.base.Supplier
import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue

/** Info item for server_log path.  */
class ServerLogInfoItem
/**
 * Constructs an info item for the server log path.
 * 
 * @param productName name of the tool whose server log path will be queried
 */
    (productName: String?) : InfoItem("server_log", productName + " server log path", false) {
    @Throws(AbruptExitException::class)
    public override fun get(
        configurationSupplier: Supplier<BuildConfigurationValue?>?,
        env: CommandEnvironment
    ): ByteArray {
        val serverLogPathService: ServerLogPathService =
            Preconditions.checkNotNull<T>(env.getRuntime().getBlazeService(ServerLogPathService::class.java))
        try {
            return print(serverLogPathService.getServerLogPath().orElse(""))
        } catch (e: IOException) {
            logger.atWarning().withCause(e).log("Failed to determine server log location")
            return print("UNKNOWN LOG LOCATION")
        }
    }

    companion object {
        private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()
    }
}
