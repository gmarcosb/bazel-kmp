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
package com.google.devtools.build.lib.actions.util

import com.google.devtools.build.lib.actions.ActionLookupKey

/**
 * An [ActionLookupKey] with a non-hermetic [SkyFunctionName] so that its value can be
 * directly injected during tests.
 */
class InjectedActionLookupKey(name: String) : ActionLookupKey {
    private val name: String

    init {
        this.name = name
    }

    public override fun functionName(): SkyFunctionName? {
        return INJECTED_ACTION_LOOKUP
    }

    public override fun getLabel(): Label {
        // Makes actions shareable.
        return Label.parseCanonicalUnchecked("//foo:" + name)
    }

    public override fun getConfigurationKey(): BuildConfigurationKey? {
        return null
    }

    override fun hashCode(): Int {
        return name.hashCode()
    }

    override fun equals(obj: Any?): Boolean {
        return obj is InjectedActionLookupKey
                && obj.name == name
    }

    override fun toString(): String {
        return "InjectedActionLookupKey:" + name
    }

    companion object {
        val INJECTED_ACTION_LOOKUP: SkyFunctionName? = SkyFunctionName.createNonHermetic("INJECTED_ACTION_LOOKUP")
    }
}
