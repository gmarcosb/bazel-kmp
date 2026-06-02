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
package com.google.devtools.build.lib.analysis.util

import com.google.devtools.build.lib.analysis.RuleDefinitionEnvironment

/**
 * Interface for supporting arbitrary custom behavior in mock rule classes.
 * 
 * 
 * See [MockRule] for details and usage instructions.
 */
interface MockRuleCustomBehavior {
    /**
     * Adds custom behavior to a mock rule class.
     * 
     * 
     * It's not necessary to call [RuleClass.Builder.build] here.
     */
    fun customize(builder: RuleClass.Builder?, env: RuleDefinitionEnvironment?)

    /**
     * Predefined behavior that populates a list of attributes.
     */
    class CustomAttributes internal constructor(attributes: Iterable<Attribute.Builder<*>?>) : MockRuleCustomBehavior {
        private val attributes: Iterable<Attribute.Builder<*>?>

        init {
            this.attributes = attributes
        }

        override fun customize(builder: RuleClass.Builder, env: RuleDefinitionEnvironment?) {
            for (attribute in attributes) {
                builder.add(attribute)
            }
        }
    }
}
