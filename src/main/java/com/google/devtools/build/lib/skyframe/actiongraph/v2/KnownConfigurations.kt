// Copyright 2018 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.skyframe.actiongraph.v2

import com.google.devtools.build.lib.analysis.AnalysisProtosV2.Configuration

/** Cache for BuildConfigurations in the action graph.  */
class KnownConfigurations internal constructor(aqueryOutputHandler: AqueryOutputHandler?) :
    BaseCache<BuildEvent?, Configuration?>(aqueryOutputHandler) {
    @Throws(InterruptedException::class)
    override fun createProto(config: BuildEvent, id: Int): Configuration {
        val configProto: BuildEventStreamProtos.Configuration =
            config.asStreamProto( /*context=*/null).getConfiguration()
        return Configuration.newBuilder()
            .setChecksum(config.eventId.getConfiguration().getId())
            .setMnemonic(configProto.getMnemonic())
            .setPlatformName(configProto.getPlatformName())
            .setIsTool(configProto.getIsTool())
            .setId(id)
            .build()
    }

    @Throws(IOException::class)
    override fun toOutput(configurationProto: Configuration?) {
        aqueryOutputHandler.outputConfiguration(configurationProto)
    }
}
