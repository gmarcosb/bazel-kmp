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
package com.google.devtools.build.lib.analysis

import com.google.devtools.build.lib.actions.Artifact

/** Utility for building location functions in tests.  */
internal class LocationFunctionBuilder(rootLabel: String?, private val multiple: Boolean) {
    private val root: Label?
    private var pathType: PathType? = LocationFunction.PathType.LOCATION
    private val labelMap: MutableMap<Label?, MutableCollection<Artifact?>?> =
        HashMap<Label?, MutableCollection<Artifact?>?>()

    init {
        this.root = Label.parseCanonicalUnchecked(rootLabel)
    }

    fun build(): LocationFunction {
        return LocationFunction(root, com.google.common.base.Suppliers.ofInstance<T?>(labelMap), pathType, multiple)
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun setPathType(pathType: PathType?): LocationFunctionBuilder {
        this.pathType = pathType
        return this
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun add(label: String?, vararg paths: String?): LocationFunctionBuilder {
        labelMap.put(
            Label.parseCanonicalUnchecked(label),
            java.util.Arrays.stream<String?>(paths)
                .map<Artifact?> { path: String? -> Companion.makeArtifact(path!!) }
                .collect(Collectors.toList()))
        return this
    }

    companion object {
        private fun makeArtifact(path: String): Artifact {
            val fs: FileSystem = InMemoryFileSystem(DigestHashFunction.SHA256)
            if (path.startsWith("/exec/out")) {
                return ActionsTestUtil.createArtifact(
                    ArtifactRoot.asDerivedRoot(fs.getPath("/exec"), RootType.OUTPUT, "out"),
                    fs.getPath(path)
                )
            } else {
                return ActionsTestUtil.createArtifact(
                    ArtifactRoot.asSourceRoot(Root.fromPath(fs.getPath("/exec"))), fs.getPath(path)
                )
            }
        }
    }
}
