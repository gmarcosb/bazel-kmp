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

import com.google.devtools.build.lib.analysis.AnalysisProtosV2

/** Cache for AspectDescriptors in the action graph.  */
class KnownAspectDescriptors
internal constructor(aqueryOutputHandler: AqueryOutputHandler?) :
    BaseCache<AspectDescriptor?, AnalysisProtosV2.AspectDescriptor?>(aqueryOutputHandler) {
    @Throws(IOException::class)
    override fun createProto(aspectDescriptor: AspectDescriptor, id: Int): AnalysisProtosV2.AspectDescriptor {
        val aspectDescriptorBuilder: AnalysisProtosV2.AspectDescriptor.Builder =
            AnalysisProtosV2.AspectDescriptor.newBuilder()
                .setId(id)
                .setName(aspectDescriptor.getAspectClass().getName())
        for (parameter in aspectDescriptor.getParameters().getAttributes().entries()) {
            val keyValuePairBuilder: KeyValuePair.Builder = KeyValuePair.newBuilder()
            keyValuePairBuilder.setKey(parameter.key).setValue(parameter.value)
            aspectDescriptorBuilder.addParameters(keyValuePairBuilder.build())
        }
        return aspectDescriptorBuilder.build()
    }

    @Throws(IOException::class)
    override fun toOutput(aspectDescriptorProto: AnalysisProtosV2.AspectDescriptor?) {
        aqueryOutputHandler.outputAspectDescriptor(aspectDescriptorProto)
    }
}
