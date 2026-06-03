// Copyright 2024 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.cmdline.Label

/** Tests for [RuleVisibility].  */
@RunWith(JUnit4::class)
class RuleVisibilityTest {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun validateAndSimplify_validates() {
        val e1: net.starlark.java.eval.EvalException? =
            org.junit.Assert.assertThrows<net.starlark.java.eval.EvalException?>(
                net.starlark.java.eval.EvalException::class.java,
                org.junit.function.ThrowingRunnable {
                    RuleVisibility.validateAndSimplify(
                        com.google.common.collect.ImmutableList.of<E?>(label("//visibility:pirvate"))
                    )
                })
        Truth.assertThat(e1)
            .hasMessageThat()
            .contains(
                "Invalid visibility label '//visibility:pirvate'; did you mean //visibility:public or"
                        + " //visibility:private?"
            )

        val e2: net.starlark.java.eval.EvalException? =
            org.junit.Assert.assertThrows<net.starlark.java.eval.EvalException?>(
                net.starlark.java.eval.EvalException::class.java,
                org.junit.function.ThrowingRunnable {
                    RuleVisibility.validateAndSimplify(
                        com.google.common.collect.ImmutableList.of<E?>(label(PUBLIC), label("//visibility:pbulic"))
                    )
                })
        Truth.assertThat(e2)
            .hasMessageThat()
            .contains(
                "Invalid visibility label '//visibility:pbulic'; did you mean //visibility:public or"
                        + " //visibility:private?"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun validateAndSimplify_simplifiesPublic() {
        assertThat(
            RuleVisibility.validateAndSimplify(
                com.google.common.collect.ImmutableList.of<E?>(
                    label(A), label(
                        PUBLIC
                    )
                )
            )
        )
            .containsExactly(label(PUBLIC))
        assertThat(
            RuleVisibility.validateAndSimplify(
                com.google.common.collect.ImmutableList.of<E?>(
                    label(PUBLIC), label(
                        B_PG
                    )
                )
            )
        )
            .containsExactly(label(PUBLIC))
        assertThat(
            RuleVisibility.validateAndSimplify(
                com.google.common.collect.ImmutableList.of<E?>(
                    label(PUBLIC), label(
                        PRIVATE
                    )
                )
            )
        )
            .containsExactly(label(PUBLIC))
        assertThat(
            RuleVisibility.validateAndSimplify(
                com.google.common.collect.ImmutableList.of<E?>(
                    label(PUBLIC), label(
                        PUBLIC
                    )
                )
            )
        )
            .containsExactly(label(PUBLIC))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun validateAndSimplify_simplifiesPrivate() {
        assertThat(
            RuleVisibility.validateAndSimplify(
                com.google.common.collect.ImmutableList.of<E?>(
                    label(A), label(
                        PRIVATE
                    )
                )
            )
        )
            .containsExactly(label(A))
        assertThat(
            RuleVisibility.validateAndSimplify(
                com.google.common.collect.ImmutableList.of<E?>(
                    label(PRIVATE), label(
                        B_PG
                    )
                )
            )
        )
            .containsExactly(label(B_PG))
        assertThat(
            RuleVisibility.validateAndSimplify(
                com.google.common.collect.ImmutableList.of<E?>(
                    label(PRIVATE), label(
                        PRIVATE
                    )
                )
            )
        )
            .containsExactly(label(PRIVATE))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun emptyListCanonicalizedToPrivate() {
        assertThat(RuleVisibility.validateAndSimplify(com.google.common.collect.ImmutableList.of<E?>()))
            .containsExactly(label(PRIVATE))
        val e: java.lang.IllegalArgumentException? =
            org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
                java.lang.IllegalArgumentException::class.java,
                org.junit.function.ThrowingRunnable { RuleVisibility.parseUnchecked(com.google.common.collect.ImmutableList.of<E?>()) })
        Truth.assertThat(e).hasMessageThat().contains("must not be empty")
    }

    // TODO(arostovtsev): we ought to uniquify the labels, but that would be an incompatible change
    // (affects query output).
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun validateAndSimplify_doesNotUniquify() {
        assertThat(
            RuleVisibility.validateAndSimplify(
                com.google.common.collect.ImmutableList.of<E?>(
                    label(A),
                    label(A)
                )
            )
        )
            .containsExactly(label(A), label(A))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun packageGroupsRuleVisibility_create_requiresValidatedSimplifiedNonConstantLabels() {
        val e1: java.lang.IllegalArgumentException? =
            org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
                java.lang.IllegalArgumentException::class.java,
                org.junit.function.ThrowingRunnable { PackageGroupsRuleVisibility.create(com.google.common.collect.ImmutableList.of<E?>()) })
        Truth.assertThat(e1).hasMessageThat().contains("must not be empty")
        val e2: java.lang.IllegalArgumentException? =
            org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
                java.lang.IllegalArgumentException::class.java,
                org.junit.function.ThrowingRunnable {
                    PackageGroupsRuleVisibility.create(
                        com.google.common.collect.ImmutableList.of<E?>(
                            label(PUBLIC), label(A)
                        )
                    )
                })
        Truth.assertThat(e2).hasMessageThat().contains("must be validated and simplified")
        val e3: java.lang.IllegalArgumentException? =
            org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
                java.lang.IllegalArgumentException::class.java,
                org.junit.function.ThrowingRunnable {
                    PackageGroupsRuleVisibility.create(
                        com.google.common.collect.ImmutableList.of<E?>(
                            label(A), label(PRIVATE)
                        )
                    )
                })
        Truth.assertThat(e3).hasMessageThat().contains("must be validated and simplified")
        val e4: java.lang.IllegalArgumentException? =
            org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
                java.lang.IllegalArgumentException::class.java,
                org.junit.function.ThrowingRunnable {
                    PackageGroupsRuleVisibility.create(
                        com.google.common.collect.ImmutableList.of<E?>(
                            label(PUBLIC)
                        )
                    )
                })
        Truth.assertThat(e4)
            .hasMessageThat()
            .contains("must not equal [\"//visibility:public\"] or [\"//visibility:private\"]")
        val e5: java.lang.IllegalArgumentException? =
            org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
                java.lang.IllegalArgumentException::class.java,
                org.junit.function.ThrowingRunnable {
                    PackageGroupsRuleVisibility.create(
                        com.google.common.collect.ImmutableList.of<E?>(
                            label(PRIVATE)
                        )
                    )
                })
        Truth.assertThat(e5)
            .hasMessageThat()
            .contains("must not equal [\"//visibility:public\"] or [\"//visibility:private\"]")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun concatenation() {
        assertThat(ruleVisibility(A, B_PG).concatWithPackage(pkg(C)))
            .isEqualTo(ruleVisibility(A, B_PG, C))

        assertThat(PUBLIC_VIS.concatWithPackage(pkg(C))).isEqualTo(PUBLIC_VIS)

        assertThat(PRIVATE_VIS.concatWithPackage(pkg(C))).isEqualTo(ruleVisibility(C))

        // Duplicates are not added, though they are preserved.
        assertThat(ruleVisibility(A, B_PG).concatWithPackage(pkg(A)))
            .isEqualTo(ruleVisibility(A, B_PG))
        assertThat(ruleVisibility(A, B_PG, B_PG, A).concatWithPackage(pkg(A)))
            .isEqualTo(ruleVisibility(A, B_PG, B_PG, A))
        assertThat(ruleVisibility(A, B_PG, B_PG, A).concatWithPackage(pkg(C)))
            .isEqualTo(ruleVisibility(A, B_PG, B_PG, A, C))
    }

    companion object {
        private fun label(labelString: String?): Label {
            return Label.parseCanonicalUnchecked(labelString)
        }

        private fun pkg(labelString: String?): PackageIdentifier {
            return label(labelString).getPackageIdentifier()
        }

        private fun ruleVisibility(vararg labelStrings: String?): RuleVisibility {
            val labels: com.google.common.collect.ImmutableList.Builder<Label?> =
                com.google.common.collect.ImmutableList.builder<Label?>()
            for (labelString in labelStrings) {
                labels.add(label(labelString))
            }
            return RuleVisibility.parseUnchecked(labels.build())
        }

        private const val A = "//a:__pkg__"

        // Package group labels are represented differently than __pkg__ labels, so cover both cases.
        private const val B_PG = "//b:pkggroup"
        private const val C = "//c:__pkg__"
        private const val PUBLIC = "//visibility:public"
        private const val PRIVATE = "//visibility:private"
        private val PUBLIC_VIS: RuleVisibility = RuleVisibility.PUBLIC
        private val PRIVATE_VIS: RuleVisibility = RuleVisibility.PRIVATE
    }
}
