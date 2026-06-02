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
package com.google.devtools.build.lib.packages

import com.google.devtools.build.lib.packages.RuleOrMacroInstance

/**
 * [AttributeMap] implementation that triggers an [IllegalStateException] if called
 * on any attribute that supports configurable values, as determined by
 * [Attribute.isConfigurable].
 * 
 * 
 * This is particularly useful for logic that doesn't have access to configurations - it
 * protects against undefined behavior in response to unexpected configuration-dependent inputs.
 */
class NonconfigurableAttributeMapper private constructor(rule: RuleOrMacroInstance) :
    com.google.devtools.build.lib.packages.AbstractAttributeMapper(rule) {
    override fun <T> get(attributeName: String?, type: com.google.devtools.build.lib.packages.Type<T?>?): T? {
        val attr: T? = super.get<T?>(attributeName, type)
        com.google.common.base.Preconditions.checkState(
            !getAttributeDefinition(attributeName).isConfigurable(),
            "Attribute '%s' is potentially configurable - not allowed here", attributeName
        )
        return attr
    }

    companion object {
        /**
         * Example usage:
         * 
         * <pre>
         * Label fooLabel = NonconfigurableAttributeMapper.of(rule).get("foo", Type.LABEL);
        </pre> * 
         */
        fun of(rule: RuleOrMacroInstance): NonconfigurableAttributeMapper {
            return NonconfigurableAttributeMapper(rule)
        }

        fun <T> attributeOrNull(
            rule: com.google.devtools.build.lib.packages.Rule,
            attributeName: String?,
            type: com.google.devtools.build.lib.packages.Type<T?>?
        ): T? {
            val mapper = of(rule)
            if (!mapper.has<T?>(attributeName, type)) {
                return null
            }
            return mapper.get<T?>(attributeName, type)
        }
    }
}
