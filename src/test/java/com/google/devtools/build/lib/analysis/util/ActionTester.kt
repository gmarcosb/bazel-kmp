// Copyright 2015 The Bazel Authors. All rights reserved.
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

import com.google.common.base.Preconditions
import com.google.common.collect.ImmutableSet
import com.google.common.collect.Sets
import com.google.devtools.build.lib.actions.Action
import com.google.errorprone.annotations.CheckReturnValue
import kotlin.collections.ArrayList
import kotlin.collections.MutableList

/**
 * Test helper for testing [Action] implementations.
 */
class ActionTester @kotlin.jvm.JvmOverloads constructor(actionKeyContext: ActionKeyContext? = ActionKeyContext()) {
    /** A generator for action instances.  */
    interface ActionCombinationFactory<E : Enum<E?>?> {
        /**
         * Returns a new action instance. The parameter `attributesToFlip` is used to vary the
         * parameters used to create the action. Implementations should do something like this: `
         * <pre>
         * private enum KeyAttributes { ATTR_1, ATTR_2, ATTR_3, ATTR_4 }
         * return new MyAction(owner, inputs, outputs, configuration,
         * attributesToFlip.contains(ATTR_0) ? a1 : a2,
         * attributesToFlip.contains(ATTR_1) ? b1 : b2,
         * attributesToFlip.contains(ATTR_2) ? c1 : c2,
         * attributesToFlip.contains(ATTR_3) ? d1 : d2);
        </pre> * 
        ` * 
         * 
         * 
         * To reduce the combinatorial complexity of testing an action class, all elements that are
         * only used to change the executed command line should go into a single parameter, and the key
         * computation should take the generated command line into account.
         * 
         * 
         * Furthermore, when called with identical parameters, this method should return different
         * instances (i.e. according to `==`), but they should have the same key.
         * 
         * @param attributesToFlip
         */
        @Throws(Exception::class)
        fun generate(attributesToFlip: ImmutableSet<E?>?): Action?
    }

    private val actionKeyContext: ActionKeyContext?
    private val actions: MutableList<Action?> = ArrayList<Action?>()

    init {
        this.actionKeyContext = actionKeyContext
    }

    /**
     * Creates all possible combinations of actions given a set of flags which can be either on or
     * off. This requires that all combinations result in different actions, i.e., all flags must be
     * orthogonal. The generated actions are added to a local list for a subsequent call to [ ][.runTest]. This method can be called multiple times to generate different sets of actions.
     */
    @CheckReturnValue
    @Throws(Exception::class)
    fun <E : Enum<E?>?> combinations(
        attributeClass: Class<E?>, factory: ActionCombinationFactory<E?>
    ): ActionTester {
        val attributesCount = attributeClass.getEnumConstants().size
        Preconditions.checkArgument(
            attributesCount <= 30,
            "Maximum attribute count is 30, more will overflow the max array size."
        )
        Preconditions.checkArgument(attributesCount > 0, "Minimum attribute count is 1")
        val count = 2.0.pow(attributesCount.toDouble()) as Int
        var firstAction: Action? = null
        for (i in 0..<count) {
            val action: Action? = factory.generate(makeEnumSetInitializedTo<E?>(attributeClass, i))
            actions.add(action)
            // Check that creating the same action twice results in equal actions.
            assertThat(
                Actions.canBeShared(
                    actionKeyContext,
                    action,
                    factory.generate(makeEnumSetInitializedTo<E?>(attributeClass, i))
                )
            )
                .isTrue()
            if (i == 0) {
                firstAction = action
            }
        }
        // Check that the count is correct.
        assertThat(
            Actions.canBeShared(
                actionKeyContext,
                firstAction,
                factory.generate(makeEnumSetInitializedTo<E?>(attributeClass, count))
            )
        )
            .isTrue()
        return this
    }

    /** Checks that all actions are different.  */
    @Throws(Exception::class)
    fun runTest() {
        Truth.assertThat(actions).isNotEmpty()
        for (i in actions.indices) {
            for (j in i + 1..<actions.size) {
                Truth.assertWithMessage("%s and %s", i, j)
                    .that(Actions.canBeShared(actionKeyContext, actions.get(i), actions.get(j)))
                    .isFalse()
            }
        }
    }

    companion object {
        /**
         * Tests that different actions have different keys. The attributeCount should specify how many
         * different permutations the [ActionCombinationFactory] should generate.
         */
        @Throws(Exception::class)
        fun <E : Enum<E?>?> runTest(
            attributeClass: Class<E?>,
            factory: ActionCombinationFactory<E?>,
            actionKeyContext: ActionKeyContext?
        ) {
            ActionTester(actionKeyContext).combinations<E?>(attributeClass, factory).runTest()
        }

        private fun <E : Enum<E?>?> makeEnumSetInitializedTo(
            attributeClass: Class<E?>, seed: Int
        ): ImmutableSet<E?> {
            val result: EnumSet<E?> = EnumSet.noneOf<E?>(attributeClass)
            val b: BitSet = BitSet.valueOf(longArrayOf(seed.toLong()))
            val attributes = attributeClass.getEnumConstants()
            for (i in attributes.indices) {
                if (b.get(i)) {
                    result.add(attributes[i])
                }
            }
            return Sets.immutableEnumSet<E?>(result)
        }
    }
}
