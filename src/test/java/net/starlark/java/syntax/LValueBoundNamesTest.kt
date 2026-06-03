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
package net.starlark.java.syntax

import com.google.common.truth.Truth
import net.starlark.java.syntax.SyntaxError.Exception.errors
import net.starlark.java.syntax.TypeTable.errors
import net.starlark.java.syntax.TypeTable.ok
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.util.stream.Collectors

/** A test for [LValue.boundIdentifiers]()}.  */
@RunWith(JUnit4::class)
class LValueBoundNamesTest {
    @org.junit.Test
    fun simpleAssignment() {
        assertBoundNames("x = 1", "x")
    }

    @org.junit.Test
    fun listAssignment() {
        assertBoundNames("x, y = 1", "x", "y")
    }

    @org.junit.Test
    fun complexListAssignment() {
        assertBoundNames("x, [y] = 1", "x", "y")
    }

    @org.junit.Test
    fun arrayElementAssignment() {
        assertBoundNames("x[1] = 1")
    }

    @org.junit.Test
    fun complexListAssignment2() {
        assertBoundNames("[[x], y], [z, w[1]] = 1", "x", "y", "z")
    }

    companion object {
        private fun assertBoundNames(assignment: String?, vararg expectedBoundNames: String?) {
            val input: net.starlark.java.syntax.ParserInput? =
                net.starlark.java.syntax.ParserInput.fromLines(assignment)
            val file: net.starlark.java.syntax.StarlarkFile = net.starlark.java.syntax.StarlarkFile.parse(input)
            if (!file.ok()) {
                throw java.lang.AssertionError(net.starlark.java.syntax.SyntaxError.Exception(file.errors()))
            }
            val lhs: net.starlark.java.syntax.Expression? =
                (file.statements.get(0) as net.starlark.java.syntax.AssignmentStatement).getLHS()
            val boundNames: MutableSet<String?> =
                net.starlark.java.syntax.Identifier.boundIdentifiers(lhs).stream()
                    .map<String?> { obj: net.starlark.java.syntax.Identifier? -> obj.getName() }
                    .collect(Collectors.toSet())
            Truth.assertThat(boundNames)
                .containsExactlyElementsIn(java.util.Arrays.asList<String?>(*expectedBoundNames))
        }
    }
}
