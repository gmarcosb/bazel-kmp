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
package com.google.devtools.build.lib.rules.proto

import com.google.common.annotations.VisibleForTesting
import com.google.devtools.build.lib.actions.Artifact
import net.starlark.java.eval.EvalException
import net.starlark.java.eval.Sequence

/**
 * Configured target classes that implement this class can contribute .proto files to the
 * compilation of proto_library rules.
 */
@Immutable
class ProtoInfo private constructor(value: StarlarkInfo) {
    /** Provider class for [ProtoInfo] objects.  */
    class ProtoInfoProvider(key: BzlLoadValue.Key?) : StarlarkProviderWrapper<ProtoInfo?>(key, "ProtoInfo") {
        @Throws(RuleErrorException::class)
        public override fun wrap(value: Info?): ProtoInfo {
            try {
                return ProtoInfo(value as StarlarkInfo?)
            } catch (e: EvalException) {
                throw RuleErrorException(e.getMessageWithStack())
            } catch (e: TypeException) {
                throw RuleErrorException(e.getMessage())
            }
        }
    }

    private val value: StarlarkInfo
    private val transitiveProtoSources: NestedSet<Artifact?>?

    init {
        this.value = value
        transitiveProtoSources =
            value.getValue("transitive_sources", Depset::class.java).getSet(Artifact::class.java)
    }

    fun getTransitiveProtoSources(): NestedSet<Artifact?>? {
        return transitiveProtoSources
    }

    @get:Throws(Exception::class)
    @get:VisibleForTesting
    val directProtoSources: ImmutableList<Artifact>?
        /** The proto source files that are used in compiling this `proto_library`.  */
        get() = Sequence.cast<T?>(
            value.getValue("direct_sources", Sequence::class.java),
            Artifact::class.java,
            "direct_sources"
        )
            .getImmutableList()

    @get:Throws(Exception::class)
    @get:VisibleForTesting
    val transitiveProtoSourceRoots: NestedSet<String?>
        get() = value.getValue("transitive_proto_path", Depset::class.java).getSet(String::class.java)

    @get:Throws(Exception::class)
    @get:VisibleForTesting
    val strictImportableProtoSourcesForDependents: NestedSet<Artifact?>
        get() = value.getValue("check_deps_sources", Depset::class.java).getSet(Artifact::class.java)

    @get:Throws(Exception::class)
    @get:VisibleForTesting
    val directDescriptorSet: Artifact
        /**
         * Be careful while using this artifact - it is the parsing of the transitive set of .proto files.
         * It's possible to cause a O(n^2) behavior, where n is the length of a proto chain-graph.
         * (remember that proto-compiler reads all transitive .proto files, even when producing the
         * direct-srcs descriptor set)
         */
        get() = value.getValue("direct_descriptor_set", Artifact::class.java)

    @get:Throws(Exception::class)
    @get:VisibleForTesting
    val transitiveDescriptorSets: NestedSet<Artifact?>
        get() = value.getValue("transitive_descriptor_sets", Depset::class.java).getSet(Artifact::class.java)
}
