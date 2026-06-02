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
package com.google.devtools.build.lib.includescanning

import com.google.common.base.Preconditions
import com.google.devtools.build.lib.includescanning.IncludeParser.Inclusion

/**
 * An [Inclusion] together with the context where on the include path the inclusion was found,
 * and whether the containing file was included using angle brackets or quotes.
 */
internal class InclusionWithContext(inclusion: Inclusion?, contextPathPos: Int, contextKind: Inclusion.Kind?) {
    val inclusion: Inclusion

    /**
     * The position on the include path on which the containing file was found. Local inclusions
     * correspond conceptually to the first entry on the include, so the values are used like this:
     * 
     *  * -1: top-level or not a `#include_next` inclusion,
     *  * 0: `#include_next` inclusion and locally found header,
     *  * >0: `#include_next` inclusion and found on the include path.
     * 
     */
    val contextPathPos: Int

    /**
     * On which include path to continue searching. For [Kind.QUOTE] and [Kind.ANGLE]
     * inclusions this is the inclusion kind itself. For [Kind.NEXT_QUOTE] and
     * [Kind.NEXT_ANGLE] inclusion it is the kind of the last inclusion that was not a
     * `#include_next` inclusion.
     */
    val contextKind: Inclusion.Kind?

    /**
     * Attaches context to an inclusion.
     * 
     * @param inclusion the inclusion
     * @param contextPathPos the position on the include path on which the containing file was found.
     * Used directly only for `#include_next` inclusions, but stored for all inclusions
     * so that include_next inclusions found inside this one can have proper context.
     * @param contextKind how the containing file was included. Used only for include_next inclusions.
     * Must not be a [Kind.NEXT_ANGLE] or [Kind.NEXT_QUOTE]
     */
    init {
        this.inclusion = Preconditions.checkNotNull<Inclusion>(inclusion)

        Preconditions.checkArgument(contextKind == null || !contextKind.isNext(), inclusion)

        this.contextPathPos = contextPathPos
        // The context kind is only stored for #include_next inclusions.
        if (this.inclusion.kind.isNext()) {
            this.contextKind = contextKind
        } else {
            this.contextKind = this.inclusion.kind
        }
    }

    /**
     * Creates a simple [Kind.QUOTE] or [Kind.ANGLE] inclusion with empty context.
     * 
     * @param name the name of the included file
     * @param kind the kind of the inclusion, must not be a `#include_next` inclusion
     */
    constructor(name: String?, kind: Inclusion.Kind?) : this(Inclusion.Companion.create(name, kind), -1, null)

    override fun toString(): String {
        return if (inclusion.kind.isNext())
            inclusion.toString() + "(" + contextKind + ":" + contextPathPos + ")"
        else
            inclusion.toString()
    }

    override fun equals(o: Any?): Boolean {
        if (o === this) {
            return true
        }
        if (o !is InclusionWithContext) {
            return false
        }
        return this.inclusion == o.inclusion
                && (!this.inclusion.kind.isNext() || this.contextPathPos == o.contextPathPos)
                && this.contextKind == o.contextKind
    }

    override fun hashCode(): Int {
        var result = 1
        result = 31 * result + inclusion.hashCode()
        result = 31 * result + (if (inclusion.kind.isNext()) Integer.hashCode(contextPathPos) else 0)
        result = 31 * result + (if (contextKind != null) contextKind.hashCode() else 0)
        return result
    }
}
