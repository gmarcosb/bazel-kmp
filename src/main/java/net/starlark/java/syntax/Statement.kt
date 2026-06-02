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
package net.starlark.java.syntax

/** Base class for all statements nodes in the AST.  */
abstract class Statement internal constructor(locs: FileLocations?, kind: Kind?) : Node(locs) {
    /**
     * Kind of the statement. This is similar to using instanceof, except that it's more efficient and
     * can be used in a switch/case.
     */
    enum class Kind {
        ASSIGNMENT,
        EXPRESSION,
        FLOW,
        FOR,
        DEF,
        IF,
        LOAD,
        RETURN,
        TYPE_ALIAS,
        VAR,
    }

    // Materialize kind as a field so its accessor can be non-virtual.
    private val kind: Kind?

    init {
        this.kind = kind
    }

    /**
     * Kind of the statement. This is similar to using instanceof, except that it's more efficient and
     * can be used in a switch/case.
     */
    // Final to avoid cost of virtual call (see #12967).
    fun kind(): Kind? {
        return kind
    }
}
