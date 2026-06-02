// Copyright 2006 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.analysis.stringtemplate

import com.google.common.collect.ImmutableSet
import com.google.common.truth.Truth
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import org.junit.function.ThrowingRunnable
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.util.function.Function
import kotlin.collections.HashMap
import kotlin.collections.MutableMap

/**
 * Unit tests for the [TemplateExpander].
 */
@RunWith(JUnit4::class)
class TemplateExpanderTest {
    private class TemplateContextImpl : TemplateContext {
        private val vars: MutableMap<String?, String?> = HashMap<String?, String?>()
        private val functions: MutableMap<String?, Function<String?, String?>?> =
            HashMap<String?, Function<String?, String?>?>()

        @Throws(ExpansionException::class)
        override fun lookupVariable(name: String): String? {
            // Not a Make variable. Let the shell handle the expansion.
            if (name.startsWith("$")) {
                return name
            }
            if (!vars.containsKey(name)) {
                throw ExpansionException(String.format("$(%s) not defined", name))
            }
            return vars.get(name)
        }

        @Throws(ExpansionException::class)
        override fun lookupFunction(name: String?, param: String?): String? {
            if (!functions.containsKey(name)) {
                throw ExpansionException(String.format("$(%s) not defined", name))
            }
            return functions.get(name)!!.apply(param)
        }
    }

    private var context: TemplateContextImpl? = null

    @Before
    @Throws(Exception::class)
    fun createContext() {
        context = TemplateContextImpl()
    }

    @Throws(ExpansionException::class, InterruptedException::class)
    private fun expand(value: String): Expansion {
        return TemplateExpander.expand(value, context!!)
    }

    @Throws(ExpansionException::class, InterruptedException::class)
    private fun expandSingleVariable(value: String?): String {
        return TemplateExpander.expandSingleVariable(value!!, context!!)!!
    }

    @Throws(InterruptedException::class)
    private fun expansionFailure(cmd: String): ExpansionException {
        try {
            expand(cmd)
            Assert.fail("Expansion of " + cmd + " didn't fail as expected")
            throw AssertionError()
        } catch (e: ExpansionException) {
            return e
        }
    }

    @Test
    @Throws(Exception::class)
    fun testVariableExpansion() {
        context.vars.put("SRCS", "src1 src2")
        context.vars.put("<", "src1")
        context.vars.put("OUTS", "out1 out2")
        context.vars.put("@", "out1")
        context.vars.put("^", "src1 src2 dep1 dep2")
        context.vars.put("@D", "outdir")
        context.vars.put("BINDIR", "bindir")
        context.vars.put("CUSTOMVAR", "custom val")

        Truth.assertThat(expand("$(SRCS)")).isEqualTo(Expansion.create("src1 src2", ImmutableSet.of<E?>("SRCS")))
        Truth.assertThat(expand("$<")).isEqualTo(Expansion.create("src1", ImmutableSet.of<E?>("<")))
        Truth.assertThat(expand("$(OUTS)")).isEqualTo(Expansion.create("out1 out2", ImmutableSet.of<E?>("OUTS")))
        Truth.assertThat(expand("$(@)")).isEqualTo(Expansion.create("out1", ImmutableSet.of<E?>("@")))
        Truth.assertThat(expand("$@")).isEqualTo(Expansion.create("out1", ImmutableSet.of<E?>("@")))
        Truth.assertThat(expand("$@,")).isEqualTo(Expansion.create("out1,", ImmutableSet.of<E?>("@")))
        Truth.assertThat(expand("$(CUSTOMVAR)"))
            .isEqualTo(Expansion.create("custom val", ImmutableSet.of<E?>("CUSTOMVAR")))

        Truth.assertThat(expand("$(SRCS) $(OUTS)"))
            .isEqualTo(Expansion.create("src1 src2 out1 out2", ImmutableSet.of<E?>("SRCS", "OUTS")))

        Truth.assertThat(expand("cmd")).isEqualTo(Expansion.create("cmd", ImmutableSet.of<E?>()))
        Truth.assertThat(expand("cmd $(SRCS),"))
            .isEqualTo(Expansion.create("cmd src1 src2,", ImmutableSet.of<E?>("SRCS")))
        Truth.assertThat(expand("label1 $(SRCS),"))
            .isEqualTo(Expansion.create("label1 src1 src2,", ImmutableSet.of<E?>("SRCS")))
        Truth.assertThat(expand(":label1 $(SRCS),"))
            .isEqualTo(Expansion.create(":label1 src1 src2,", ImmutableSet.of<E?>("SRCS")))
    }

    @Test
    @Throws(Exception::class)
    fun testUndefinedVariableExpansion() {
        Truth.assertThat(expansionFailure("$(foo)"))
            .hasMessageThat().isEqualTo("$(foo) not defined")
    }

    @Test
    @Throws(Exception::class)
    fun testFunctionExpansion() {
        context.functions.put("foo", Function { p: String? -> "FOO(" + p + ")" })
        context.vars.put("bar", "bar")

        Truth.assertThat(expand("$(foo baz)"))
            .isEqualTo(Expansion.create("FOO(baz)", ImmutableSet.of<E?>("foo")))
        Truth.assertThat(expand("$(bar) $(foo baz)"))
            .isEqualTo(Expansion.create("bar FOO(baz)", ImmutableSet.of<E?>("bar", "foo")))
        Truth.assertThat(expand("xyz$(foo baz)zyx"))
            .isEqualTo(Expansion.create("xyzFOO(baz)zyx", ImmutableSet.of<E?>("foo")))
    }

    @Test
    @Throws(Exception::class)
    fun testFunctionExpansionThrows() {
        val e =
            Assert.assertThrows<ExpansionException?>(
                ExpansionException::class.java,
                ThrowingRunnable {
                    TemplateExpander.expand(
                        "$(foo baz)",
                        object : TemplateContext() {
                            @Throws(ExpansionException::class)
                            override fun lookupVariable(name: String?): String? {
                                throw ExpansionException(name)
                            }

                            @Throws(ExpansionException::class)
                            override fun lookupFunction(name: String?, param: String?): String? {
                                throw ExpansionException(name + "(" + param + ")")
                            }
                        })
                })
        Truth.assertThat(e).hasMessageThat().isEqualTo("foo(baz)")
    }

    @Test
    @Throws(Exception::class)
    fun testUndefinedFunctionExpansion() {
        // Note: $(location x) is considered an undefined variable;
        Truth.assertThat(expansionFailure("$(location label1), $(SRCS),"))
            .hasMessageThat().isEqualTo("$(location) not defined")
        Truth.assertThat(expansionFailure("$(basename file)"))
            .hasMessageThat().isEqualTo("$(basename) not defined")
    }

    @Test
    @Throws(Exception::class)
    fun testRecursiveExpansion() {
        // Expansion is recursive: $(recursive) -> $(SRCS) -> "src1 src2"
        context.vars.put("SRCS", "src1 src2")
        context.vars.put("recursive", "$(SRCS)")
        Truth.assertThat(expand("$(recursive)"))
            .isEqualTo(Expansion.create("src1 src2", ImmutableSet.of<E?>("recursive", "SRCS")))
    }

    @Test
    @Throws(Exception::class)
    fun testRecursiveExpansionDoesNotSpanExpansionBoundaries() {
        // Recursion does not span expansion boundaries:
        // $(recur2a)$(recur2b) --> "$" + "(SRCS)"  --/--> "src1 src2"
        context.vars.put("SRCS", "src1 src2")
        context.vars.put("recur2a", "$$")
        context.vars.put("recur2b", "(SRCS)")
        Truth.assertThat(expand("$(recur2a)$(recur2b)"))
            .isEqualTo(Expansion.create("$(SRCS)", ImmutableSet.of<E?>("recur2a", "recur2b")))
    }

    @Test
    @Throws(Exception::class)
    fun testSelfInfiniteExpansionFailsGracefully() {
        context.vars.put("infinite", "$(infinite)")
        Truth.assertThat(expansionFailure("$(infinite)")).hasMessageThat()
            .isEqualTo("potentially unbounded recursion during expansion of '$(infinite)'")
    }

    @Test
    @Throws(Exception::class)
    fun testMutuallyInfiniteExpansionFailsGracefully() {
        context.vars.put("black", "$(white)")
        context.vars.put("white", "$(black)")
        Truth.assertThat(expansionFailure("$(white) is the new $(black)")).hasMessageThat()
            .isEqualTo("potentially unbounded recursion during expansion of '$(black)'")
    }

    @Test
    @Throws(Exception::class)
    fun testErrors() {
        Truth.assertThat(expansionFailure("$(SRCS")).hasMessageThat()
            .isEqualTo("unterminated variable reference")
        Truth.assertThat(expansionFailure("$")).hasMessageThat().isEqualTo("unterminated $")

        val suffix = ("instead for \"Make\" variables, or escape the '$' as '$$' if you intended "
                + "this for the shell")
        Truth.assertThat(expansionFailure("for file in a b c;do echo \$file;done")).hasMessageThat()
            .isEqualTo("'\$file' syntax is not supported; use '$(file)' " + suffix)
        Truth.assertThat(expansionFailure("\${file%:.*8}")).hasMessageThat()
            .isEqualTo("'\${file%:.*8}' syntax is not supported; use '$(file%:.*8)' " + suffix)
    }

    @Test
    @Throws(Exception::class)
    fun testDollarDollar() {
        Truth.assertThat(expand("for file in a b c;do echo $\$file;done"))
            .isEqualTo(Expansion.create("for file in a b c;do echo \$file;done", ImmutableSet.of<E?>()))
        Truth.assertThat(expand("$\${file%:.*8}"))
            .isEqualTo(Expansion.create("\${file%:.*8}", ImmutableSet.of<E?>()))
        Truth.assertThat(expand("$$(basename file)"))
            .isEqualTo(Expansion.create("$(basename file)", ImmutableSet.of<E?>()))
    }

    // Regression test: check that the parameter is trimmed before expanding.
    @Test
    @Throws(Exception::class)
    fun testFunctionExpansionIsTrimmed() {
        context.functions.put("foo", Function { p: String? -> "FOO(" + p + ")" })
        Truth.assertThat(expand("$(foo  baz )"))
            .isEqualTo(Expansion.create("FOO(baz)", ImmutableSet.of<E?>("foo")))
    }

    @Test
    @Throws(Exception::class)
    fun testExpandSingleVariable() {
        context.vars.put("SINGLE", "val1 val2")
        Truth.assertThat(expandSingleVariable("$(SINGLE)")).isEqualTo("val1 val2")
        Truth.assertThat(expandSingleVariable("foo $(SINGLE)")).isNull()
    }
}
