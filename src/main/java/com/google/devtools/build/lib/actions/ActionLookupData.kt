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
package com.google.devtools.build.lib.actions

import com.google.devtools.build.lib.cmdline.Label

/**
 * Data that uniquely identifies an action.
 * 
 * 
 * [.getActionLookupKey] returns the [ActionLookupKey] to look up an [ ]. [.getActionIndex] returns the index of the action within [ ][ActionLookupValue.getActions].
 * 
 * 
 * To save memory, a custom subclass without an `int` field is used for the most common
 * action indices [0-9].
 */
abstract class ActionLookupData private constructor(actionLookupKey: ActionLookupKey?) : ExecutionPhaseSkyKey {
    private val actionLookupKey: ActionLookupKey

    init {
        this.actionLookupKey = com.google.common.base.Preconditions.checkNotNull<ActionLookupKey>(actionLookupKey)
    }

    fun getActionLookupKey(): ActionLookupKey {
        return actionLookupKey
    }

    /**
     * Index of the action in question in the node keyed by [.getActionLookupKey]. Should be
     * passed to [ActionLookupValue.getAction].
     */
    abstract fun getActionIndex(): Int

    fun getLabel(): Label? {
        return actionLookupKey.getLabel()
    }

    override fun hashCode(): Int {
        var hash = 1
        hash = 37 * hash + actionLookupKey.hashCode()
        hash = 37 * hash + java.lang.Integer.hashCode(getActionIndex())
        hash = 37 * hash + java.lang.Boolean.hashCode(valueIsShareable())
        return hash
    }

    override fun equals(obj: Any?): Boolean {
        if (this === obj) {
            return true
        }
        if (obj !is ActionLookupData) {
            return false
        }
        return getActionIndex() == obj.getActionIndex() && actionLookupKey == obj.actionLookupKey
                && valueIsShareable() === obj.valueIsShareable()
    }

    override fun toString(): String {
        return com.google.common.base.MoreObjects.toStringHelper(this)
            .add("actionLookupKey", actionLookupKey)
            .add("actionIndex", getActionIndex())
            .toString()
    }

    public override fun functionName(): SkyFunctionName {
        return SkyFunctions.ACTION_EXECUTION
    }

    private class ActionLookupData0(actionLookupKey: ActionLookupKey?) : ActionLookupData(actionLookupKey) {
        override fun getActionIndex(): Int {
            return 0
        }
    }

    private class ActionLookupData1(actionLookupKey: ActionLookupKey?) : ActionLookupData(actionLookupKey) {
        override fun getActionIndex(): Int {
            return 1
        }
    }

    private class ActionLookupData2(actionLookupKey: ActionLookupKey?) : ActionLookupData(actionLookupKey) {
        override fun getActionIndex(): Int {
            return 2
        }
    }

    private class ActionLookupData3(actionLookupKey: ActionLookupKey?) : ActionLookupData(actionLookupKey) {
        override fun getActionIndex(): Int {
            return 3
        }
    }

    private class ActionLookupData4(actionLookupKey: ActionLookupKey?) : ActionLookupData(actionLookupKey) {
        override fun getActionIndex(): Int {
            return 4
        }
    }

    private class ActionLookupData5(actionLookupKey: ActionLookupKey?) : ActionLookupData(actionLookupKey) {
        override fun getActionIndex(): Int {
            return 5
        }
    }

    private class ActionLookupData6(actionLookupKey: ActionLookupKey?) : ActionLookupData(actionLookupKey) {
        override fun getActionIndex(): Int {
            return 6
        }
    }

    private class ActionLookupData7(actionLookupKey: ActionLookupKey?) : ActionLookupData(actionLookupKey) {
        override fun getActionIndex(): Int {
            return 7
        }
    }

    private class ActionLookupData8(actionLookupKey: ActionLookupKey?) : ActionLookupData(actionLookupKey) {
        override fun getActionIndex(): Int {
            return 8
        }
    }

    private class ActionLookupData9(actionLookupKey: ActionLookupKey?) : ActionLookupData(actionLookupKey) {
        override fun getActionIndex(): Int {
            return 9
        }
    }

    private open class ActionLookupDataN(actionLookupKey: ActionLookupKey?, private val actionIndex: Int) :
        ActionLookupData(actionLookupKey) {
        override fun getActionIndex(): Int {
            return actionIndex
        }
    }

    private class UnshareableActionLookupData(actionLookupKey: ActionLookupKey?, actionIndex: Int) :
        ActionLookupDataN(actionLookupKey, actionIndex) {
        public override fun valueIsShareable(): Boolean {
            return false
        }
    }

    companion object {
        /**
         * Creates a key for the result of action execution. Does *not* intern its results, so should
         * only be called once per `(actionLookupKey, actionIndex)` pair.
         */
        fun create(actionLookupKey: ActionLookupKey, actionIndex: Int): ActionLookupData {
            if (!actionLookupKey.mayOwnShareableActions()) {
                return createUnshareable(actionLookupKey, actionIndex)
            }
            return when (actionIndex) {
                0 -> ActionLookupData0(actionLookupKey)
                1 -> ActionLookupData1(actionLookupKey)
                2 -> ActionLookupData2(actionLookupKey)
                3 -> ActionLookupData3(actionLookupKey)
                4 -> ActionLookupData4(actionLookupKey)
                5 -> ActionLookupData5(actionLookupKey)
                6 -> ActionLookupData6(actionLookupKey)
                7 -> ActionLookupData7(actionLookupKey)
                8 -> ActionLookupData8(actionLookupKey)
                9 -> ActionLookupData9(actionLookupKey)
                else -> ActionLookupDataN(actionLookupKey, actionIndex)
            }
        }

        /**
         * Similar to [.create], but the key will return `false` for [ ][.valueIsShareable].
         */
        fun createUnshareable(
            actionLookupKey: ActionLookupKey?, actionIndex: Int
        ): ActionLookupData {
            return UnshareableActionLookupData(actionLookupKey, actionIndex)
        }
    }
}
