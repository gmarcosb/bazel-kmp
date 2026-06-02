// Copyright 2017 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.cmdline.Label

/**
 * A key requesting transitive loading of all dependencies of a given label; see
 * [TransitiveTargetFunction] and [TransitiveTargetValue].
 */
@Immutable
@ThreadSafe
class TransitiveTargetKey private constructor(label: Label?) : SkyKey {
    private val label: Label

    init {
        this.label = com.google.common.base.Preconditions.checkNotNull<Label>(label)
    }

    override fun functionName(): SkyFunctionName {
        return NAME
    }

    override fun argument(): Any {
        return this
    }

    fun getLabel(): Label {
        return label
    }

    override fun toString(): String {
        return com.google.common.base.MoreObjects.toStringHelper(this).add("label", label).toString()
    }

    override fun hashCode(): Int {
        return 31 * functionName().hashCode() + label.hashCode()
    }

    override fun equals(o: Any?): Boolean {
        if (o === this) {
            return true
        }
        if (o !is TransitiveTargetKey) {
            return false
        }
        return o.label.equals(label)
    }

    val skyKeyInterner: SkyKeyInterner<*>
        get() = interner

    companion object {
        @kotlin.jvm.JvmField
        val NAME: SkyFunctionName = SkyFunctionName.createHermetic("TRANSITIVE_TARGET")

        private val interner: SkyKeyInterner<TransitiveTargetKey?> = SkyKey.newInterner<TransitiveTargetKey?>()

        fun of(label: Label?): TransitiveTargetKey {
            return interner.intern(TransitiveTargetKey(label))
        }
    }
}
