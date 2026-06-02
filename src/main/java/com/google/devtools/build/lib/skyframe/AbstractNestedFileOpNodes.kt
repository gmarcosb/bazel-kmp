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
package com.google.devtools.build.lib.skyframe

import com.google.devtools.build.lib.skyframe.FileOpNodeOrFuture.EmptyFileOpNode
import com.google.devtools.build.lib.skyframe.FileOpNodeOrFuture.FileOpNode
import com.google.devtools.build.lib.skyframe.FileOpNodeOrFuture.FileOpNodeOrEmpty

/**
 * Represents a collection of [FileOpNode]s, allowing for nested structures to represent
 * complex file dependencies.
 * 
 * 
 * This class serves as a container for multiple [FileOpNode] instances, enabling the
 * representation of file operation dependencies in a hierarchical manner. It differentiates between
 * analysis dependencies (for example, BUILD and .bzl files) and "source" dependencies, used during
 * execution (for example, .cpp, .h or .java files). It keeps them together to optimize storage.
 * 
 * 
 * **Source vs. Analysis Dependencies:**
 * 
 * 
 *  * **Analysis:** During the analysis phase, source files are declared, but configured
 * targets (which define actions) do not depend on the *contents* of these source files,
 * for example, .cpp, .h or .java files.
 *  * **Execution:** The execution phase creates actual dependencies on the contents of source
 * files as actions are run.
 * 
 * 
 * 
 * **Why combine them?** <br></br>
 * Logically, source and analysis dependencies could be tracked separately with different [ ]s. However, this would duplicate the dependency graph structure in persistent storage,
 * which is expensive. This class keeps them together, trading off a bit of complexity for reduced
 * storage overhead. The structure is written only once, and the interpretation of dependencies must
 * be handled by the client.
 * 
 * 
 * **Subclasses:**
 * 
 * 
 *  * [NestedFileOpNodes]: Represents a set of [FileOpNode]s without any immediate
 * source file dependencies.
 *  * [NestedFileOpNodesWithSource]: Represents a set of [FileOpNode]s along with an
 * immediate source file dependency ([FileKey]s).
 * 
 */
abstract class AbstractNestedFileOpNodes private constructor(analysisDependencies: Array<FileOpNode?>) : FileOpNode {
    private val analysisDependencies: Array<FileOpNode?>

    /**
     * Opaque storage for use by serialization.
     * 
     * 
     * [FileOpNode], [FileKey] and [DirectoryListingKey] are mutually dependent
     * via [FileOpNode]. This type is opaque to avoid forcing [FileKey] and [ ] to depend on serialization implementation code.
     * 
     * 
     * The serialization implementation initializes this field with double-checked locking so it is
     * marked volatile.
     */
    @kotlin.concurrent.Volatile
    var serializationScratch: Any? = null

    init {
        this.analysisDependencies = analysisDependencies
    }

    fun analysisDependenciesCount(): Int {
        return analysisDependencies.size
    }

    fun getAnalysisDependency(index: Int): FileOpNode? {
        return analysisDependencies[index]
    }

    /** A set of [FileOpNode]s with no immediate source dependencies.  */
    class NestedFileOpNodes private constructor(analysisDependencies: Array<FileOpNode?>) :
        AbstractNestedFileOpNodes(analysisDependencies) {
        init {
            com.google.common.base.Preconditions.checkArgument(analysisDependencies.size > 0)
        }
    }

    /** A set of analysis dependencies and source file dependency.  */
    class NestedFileOpNodesWithSource private constructor(
        nodes: Array<FileOpNode?>,
        source: com.google.devtools.build.lib.skyframe.FileKey?
    ) : AbstractNestedFileOpNodes(nodes) {
        private val source: com.google.devtools.build.lib.skyframe.FileKey?

        init {
            this.source = source
        }

        fun source(): com.google.devtools.build.lib.skyframe.FileKey? {
            return source
        }
    }

    companion object {
        /**
         * Effectively, a factory method for [NestedFileOpNodes], but formally a factory method for
         * [FileOpNodeOrEmpty].
         * 
         * 
         * Returns [EMPTY_FILE_OP_NODE] if `analysisDependencies` is empty. When `analysisDependencies` contains only one node, returns the node directly instead of wrapping it.
         * Otherwise, returns a [NestedFileOpNodes] instance wrapping `analysisDependencies`.
         */
        fun from(analysisDependencies: MutableCollection<FileOpNode?>): FileOpNodeOrEmpty? {
            if (analysisDependencies.isEmpty()) {
                return EmptyFileOpNode.EMPTY_FILE_OP_NODE
            }
            if (analysisDependencies.size == 1) {
                return analysisDependencies.iterator().next()
            }
            return NestedFileOpNodes(analysisDependencies.toArray<FileOpNode?>(java.util.function.IntFunction { _Dummy_.__Array__() }))
        }

        /**
         * Creates [NestedFileOpNodesWithSource] with reductions similar to [ ][.from].
         */
        fun from(
            analysisDependencies: MutableCollection<FileOpNode?>,
            source: com.google.devtools.build.lib.skyframe.FileKey?
        ): FileOpNodeOrEmpty? {
            if (source == null) {
                return from(analysisDependencies)
            }
            // It's unclear if `analysisDependencies` can ever be empty here in practice, but it's
            // permitted. It should be rare enough that defining a special type for it isn't worth it.
            return NestedFileOpNodesWithSource(
                analysisDependencies.toArray<FileOpNode?>(java.util.function.IntFunction { _Dummy_.__Array__() }),
                source
            )
        }
    }
}
