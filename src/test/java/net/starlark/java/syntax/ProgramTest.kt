// Copyright 2025 The Bazel Authors. All rights reserved.
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
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/** Tests of the Starlark [Program].  */
@RunWith(JUnit4::class)
class ProgramTest {
    @Throws(net.starlark.java.syntax.SyntaxError.Exception::class)
    private fun compileFile(vararg lines: String?): net.starlark.java.syntax.Program {
        val input: net.starlark.java.syntax.ParserInput? = net.starlark.java.syntax.ParserInput.fromLines(*lines)
        val file: net.starlark.java.syntax.StarlarkFile? =
            net.starlark.java.syntax.StarlarkFile.parse(input, net.starlark.java.syntax.FileOptions.DEFAULT)
        return net.starlark.java.syntax.Program.compileFile(
            file,
            net.starlark.java.syntax.TestUtils.Module.Companion.withPredeclared("pre")
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun docComments_basicFunctionality() {
        val program: net.starlark.java.syntax.Program =
            compileFile(
                """
            #: Doc comment for A
            #: multiline
            FOO = 1
            BAR, BAZ = (2, 3)  #: Applies to LHS list
            
            """.trimIndent()
            )
        assertThat(program.docCommentsMap.keySet()).containsExactly("FOO", "BAR", "BAZ").inOrder()
        assertThat(program.docCommentsMap.get("FOO").getText())
            .isEqualTo("Doc comment for A\nmultiline")
        assertThat(program.docCommentsMap.get("BAR").getText()).isEqualTo("Applies to LHS list")
        assertThat(program.docCommentsMap.get("BAZ").getText()).isEqualTo("Applies to LHS list")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun docComments_unused() {
        val program: net.starlark.java.syntax.Program =
            compileFile(
                """
            #: Unused - separated by a non-doc comment line
            # Non-doc comment line - not in module.unassignedDocComments()
            A = 1

            #: Unused - overridden by trailing doc comment
            B = 2  #: Trailing doc comment for B overrides preceding doc comment block

            def func():
                #: Unused - not a global assignment
                C = 3
            # Another non-doc comment line - not in module.unassignedDocComments()
            
            """.trimIndent()
            )

        Truth.assertThat(program.getDocCommentsMap().keys).containsExactly("B")
        assertThat(program.docCommentsMap.get("B").getText())
            .isEqualTo("Trailing doc comment for B overrides preceding doc comment block")
        assertThat(
            program.unusedDocCommentLines.stream()
                .map({ obj: net.starlark.java.syntax.Comment? -> obj.getDocCommentText() })
        )
            .containsExactly(
                "Unused - separated by a non-doc comment line",
                "Unused - overridden by trailing doc comment",
                "Unused - not a global assignment"
            )
            .inOrder()
    }
}
