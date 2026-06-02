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
package com.google.devtools.build.lib.actions

import com.google.common.truth.Truth
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.util.HashMap

/** [ActionEnvironment]Test  */
@RunWith(JUnit4::class)
class ActionEnvironmentTest {
    @org.junit.Test
    fun compoundEnvOrdering() {
        val env1: ActionEnvironment =
            ActionEnvironment.create(
                com.google.common.collect.ImmutableMap.of<K?, V?>("FOO", "foo1", "BAR", "bar"),
                com.google.common.collect.ImmutableSet.of<E?>("baz")
            )
        // entries added by env2 override the existing entries
        val env2: ActionEnvironment =
            env1.withAdditionalFixedVariables(com.google.common.collect.ImmutableMap.of<K?, V?>("FOO", "foo2"))

        assertThat(env1.getFixedEnv()).containsExactly("FOO", "foo1", "BAR", "bar")
        assertThat(env1.getInheritedEnv()).containsExactly("baz")

        assertThat(env2.getFixedEnv()).containsExactly("FOO", "foo2", "BAR", "bar")
        assertThat(env2.getInheritedEnv()).containsExactly("baz")
    }

    @org.junit.Test
    fun fixedInheritedInteraction() {
        val env: ActionEnvironment =
            ActionEnvironment.create(
                com.google.common.collect.ImmutableMap.of<K?, V?>("FIXED_ONLY", "fixed"),
                com.google.common.collect.ImmutableSet.of<E?>("INHERITED_ONLY", "FIXED_AND_INHERITED")
            )
                .withAdditionalFixedVariables(
                    com.google.common.collect.ImmutableMap.of<K?, V?>(
                        "FIXED_AND_INHERITED",
                        "fixed"
                    )
                )
        val clientEnv: MutableMap<String?, String?> =
            com.google.common.collect.ImmutableMap.of<String?, String?>(
                "INHERITED_ONLY",
                "inherited",
                "FIXED_AND_INHERITED",
                "inherited"
            )
        val result: MutableMap<String?, String?> = HashMap<String?, String?>()
        env.resolve(result, clientEnv)

        Truth.assertThat(result)
            .containsExactly(
                "FIXED_ONLY",
                "fixed",
                "FIXED_AND_INHERITED",
                "inherited",
                "INHERITED_ONLY",
                "inherited"
            )
    }

    @org.junit.Test
    fun emptyEnvironmentInterning() {
        val emptyEnvironment: ActionEnvironment? =
            ActionEnvironment.create(
                com.google.common.collect.ImmutableMap.of<K?, V?>(),
                com.google.common.collect.ImmutableSet.of<E?>()
            )
        assertThat(emptyEnvironment).isSameInstanceAs(ActionEnvironment.EMPTY)

        val base: ActionEnvironment =
            ActionEnvironment.create(
                com.google.common.collect.ImmutableMap.of<K?, V?>("FOO", "foo1"),
                com.google.common.collect.ImmutableSet.of<E?>("baz")
            )
        assertThat(base.withAdditionalFixedVariables(com.google.common.collect.ImmutableMap.of<K?, V?>())).isSameInstanceAs(
            base
        )
    }
}
