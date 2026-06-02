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

import com.google.common.collect.ImmutableList

/** Syntax node for a load statement.  */
class LoadStatement internal constructor(
    locs: FileLocations?,
    loadOffset: Int,
    module: StringLiteral?,
    bindings: ImmutableList<Binding?>?,
    rparenOffset: Int
) : Statement(locs, Kind.LOAD) {
    /**
     * Binding represents a binding in a load statement. load("...", local = "orig")
     * 
     * 
     * If there's no alias, a single Identifier can be used for both local and orig.
     * TODO(adonovan): don't do that; be faithful to source.
     */
    class Binding internal constructor(localName: Identifier?, originalName: Identifier?) {
        @kotlin.jvm.JvmField
        private val local: Identifier?
        private val orig: Identifier?

        fun getLocalName(): Identifier? {
            return local
        }

        fun getOriginalName(): Identifier? {
            return orig
        }

        init {
            this.local = localName
            this.orig = originalName
        }
    }

    private val loadOffset: Int
    @kotlin.jvm.JvmField
    private val module: StringLiteral?
    @kotlin.jvm.JvmField
    private val bindings: ImmutableList<Binding?>?
    private val rparenOffset: Int

    init {
        this.loadOffset = loadOffset
        this.module = module
        this.bindings = bindings
        this.rparenOffset = rparenOffset
    }

    fun getBindings(): ImmutableList<Binding?>? {
        return bindings
    }

    fun getImport(): StringLiteral? {
        return module
    }

    override fun getStartOffset(): Int {
        return loadOffset
    }

    override fun getEndOffset(): Int {
        return rparenOffset + 1
    }

    override fun accept(visitor: NodeVisitor) {
        visitor.visit(this)
    }
}
