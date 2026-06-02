// Copyright 2016 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.actions.ActionAnalysisMetadata

/** Value that stores expanded actions from ActionTemplate.  */
class ActionTemplateExpansionValue internal constructor(generatingActions: com.google.common.collect.ImmutableList<ActionAnalysisMetadata?>?) :
    BasicActionLookupValue(generatingActions) {
    /** Key for [ActionTemplateExpansionValue] nodes.  */
    @AutoCodec
    class ActionTemplateExpansionKey private constructor(actionLookupKey: ActionLookupKey, actionIndex: Int) :
        ActionLookupKey {
        private val actionLookupKey: ActionLookupKey

        /**
         * Index of the action in question in the node keyed by [.getActionLookupKey]. Should be
         * passed to [com.google.devtools.build.lib.actions.ActionLookupValue.getAction].
         */
        val actionIndex: Int

        init {
            this.actionLookupKey = actionLookupKey
            this.actionIndex = actionIndex
        }

        public override fun functionName(): SkyFunctionName {
            return SkyFunctions.ACTION_TEMPLATE_EXPANSION
        }

        val label: Label
            get() = actionLookupKey.getLabel()

        val configurationKey: BuildConfigurationKey
            get() = actionLookupKey.getConfigurationKey()

        fun getActionLookupKey(): ActionLookupKey {
            return actionLookupKey
        }

        val skyKeyInterner: SkyKeyInterner<ActionTemplateExpansionKey?>
            get() = interner

        override fun hashCode(): Int {
            return 37 * actionLookupKey.hashCode() + actionIndex
        }

        override fun equals(obj: Any?): Boolean {
            if (this === obj) {
                return true
            }
            if (obj !is ActionTemplateExpansionKey) {
                return false
            }
            return this.actionIndex == obj.actionIndex
                    && this.actionLookupKey.equals(obj.actionLookupKey)
        }

        override fun toString(): String {
            return com.google.common.base.MoreObjects.toStringHelper(this)
                .add("actionLookupKey", actionLookupKey)
                .add("actionIndex", actionIndex)
                .toString()
        }

        companion object {
            private val interner: SkyKeyInterner<ActionTemplateExpansionKey?> = SkyKey.newInterner<SkyKey?>()

            @com.google.common.annotations.VisibleForTesting
            fun of(actionLookupKey: ActionLookupKey, actionIndex: Int): ActionTemplateExpansionKey {
                return interner.intern(ActionTemplateExpansionKey(actionLookupKey, actionIndex))
            }

            @com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization
            @AutoCodec.Interner
            fun intern(key: ActionTemplateExpansionKey?): ActionTemplateExpansionKey {
                return interner.intern(key)
            }
        }
    }

    companion object {
        fun key(actionLookupKey: ActionLookupKey, actionIndex: Int): ActionTemplateExpansionKey {
            return ActionTemplateExpansionKey.Companion.of(actionLookupKey, actionIndex)
        }
    }
}
