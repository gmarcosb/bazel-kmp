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
// limitations under the License.result.add("--remote_info_request");
package com.google.devtools.build.lib.runtime.commands.info

import com.google.common.collect.ImmutableList
import com.google.devtools.build.lib.runtime.CommandEnvironment

/** Collects [InfoItem]s and sends them to the remote client via response extensions.  */
internal class RemoteRequestedInfoItemHandler(env: CommandEnvironment, printKeys: Boolean) : InfoItemHandler {
    private val env: CommandEnvironment
    private val infoItemsBuilder: ImmutableList.Builder<InfoItem?>
    private val printKeys: Boolean

    init {
        this.env = env
        this.infoItemsBuilder = ImmutableList.builder<InfoItem?>()
        this.printKeys = printKeys
    }

    override fun addInfoItem(key: String?, value: ByteArray) {
        infoItemsBuilder.add(
            InfoItem.newBuilder().setKey(key).setValue(ByteString.copyFrom(value)).build()
        )
    }

    @Throws(IOException::class)
    override fun close() {
        val infoItems: ImmutableList<InfoItem?> = infoItemsBuilder.build()
        val infoResponse: InfoResponse? =
            InfoResponse.newBuilder()
                .addAllPathToReplace(PathToReplaceUtils.getPathsToReplace(env))
                .addAllInfoItem(infoItems)
                .setPrintKeys(printKeys)
                .build()

        logger.atFine().log(
            "Blaze info is invoked by a remote client. InfoResponse = %s",
            TextFormat.printer().emittingSingleLine(true).printToString(infoResponse)
        )

        env.addResponseExtensions(ImmutableList.of<E?>(Any.pack(infoResponse)))
    }

    companion object {
        private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()
    }
}
