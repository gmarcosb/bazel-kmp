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
package net.starlark.java.syntax

import com.google.common.truth.Truth
import com.google.devtools.build.lib.exec.util.SpawnBuilder.build
import com.google.devtools.common.options.testing.ConverterTesterMap.Builder.build
import net.starlark.java.syntax.FileOptions.Builder.build
import net.starlark.java.syntax.SyntaxError.Exception.errors
import net.starlark.java.syntax.TypeTable.errors
import net.starlark.java.syntax.TypeTable.ok
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/** Tests for `NodeVisitor`  */
@RunWith(JUnit4::class)
class NodeVisitorTest {
    var gathererFactory: java.util.function.Supplier<IdentGatherer> = java.util.function.Supplier { IdentGatherer() }

    var fileOptions: net.starlark.java.syntax.FileOptions? =
        net.starlark.java.syntax.FileOptions.builder().allowTypeSyntax(true).build()

    @Throws(net.starlark.java.syntax.SyntaxError.Exception::class)
    private fun parse(vararg lines: String?): net.starlark.java.syntax.StarlarkFile {
        val input: net.starlark.java.syntax.ParserInput? = net.starlark.java.syntax.ParserInput.fromLines(*lines)
        val file: net.starlark.java.syntax.StarlarkFile =
            net.starlark.java.syntax.StarlarkFile.parse(input, fileOptions)
        if (!file.ok()) {
            throw net.starlark.java.syntax.SyntaxError.Exception(file.errors())
        }
        return file
    }

    /** Records all identifiers in the order they were seen, including duplicates.  */
    private class IdentGatherer : net.starlark.java.syntax.NodeVisitor() {
        val idents: MutableList<String?> = java.util.ArrayList<String?>()

        override fun visit(node: net.starlark.java.syntax.Identifier) {
            idents.add(node.getName())
        }

        companion object {
            fun skippingNonSymbolIdentifiers(): IdentGatherer {
                val gatherer = IdentGatherer()
                gatherer.skipNonSymbolIdentifiers = true
                return gatherer
            }
        }
    }

    /**
     * Asserts that the traversed identifiers (in order, including duplicates) of the given source
     * code match the expected identifiers, which is supplied as a space-delimited string.
     */
    @Throws(java.lang.Exception::class)
    fun assertIdentsAre(src: String?, expectedIdents: String) {
        val file: net.starlark.java.syntax.StarlarkFile = parse(src)
        val visitor: IdentGatherer = gathererFactory.get()
        visitor.visit(file)
        Truth.assertThat(visitor.idents)
            .containsExactlyElementsIn(expectedIdents.split(" ".toRegex()).dropLastWhile { it.isEmpty() }
                .toTypedArray()).inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun blockAndSimpleStatements() {
        assertIdentsAre(
            """
        load("...", "a", b="c")
        d = e
        f
        pass
        g, h[i] = j + 1 + "xyz" + 0.0
        
        """.trimIndent(),  // "c" is omitted because we don't currently visit the original name in a load binding.
            "a b d e f g h i j"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun controlStatements() {
        assertIdentsAre(
            """
        for a in b:
          if c:
            break
          else:
            continue
        
        """.trimIndent(),
            "a b c"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun simpleExpressions() {
        assertIdentsAre(
            """
        a + b if c else d.e
        {f: g, h: [i, j]}
        not k[l:m]
        
        """.trimIndent(),
            "a b c d e f g h i j k l m"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun comprehensions() {
        assertIdentsAre(
            """
        [a for b, c in d if e for f in {g: h for i in j}]
        
        """.trimIndent(),
            "a b c d e f g h i j"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun calls() {
        assertIdentsAre(
            """
        a(b, c=d, *e, **f)
        
        """.trimIndent(),
            "a b c d e f"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun functionDefs() {
        assertIdentsAre(
            """
        def a(b, c=d, *e, **f):
          g
        
        """.trimIndent(),
            "a b c d e f g"
        )
        assertIdentsAre(
            """
        def a(*, b, c=d):
          return
        
        """.trimIndent(),
            "a b c d"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun typeAnnotations() {
        assertIdentsAre(
            """
        def a[b, c](d : e[f], g: h) -> i:
          pass
        
        """.trimIndent(),
            "a b c d e f g h i"
        )

        assertIdentsAre(
            """
        a : b
        c : d = e
        
        """.trimIndent(),
            "a b c d e"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun typeDeclarations() {
        assertIdentsAre(
            """
        type a[b, c] = d
        
        """.trimIndent(),
            "a b c d"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun ellipsis() {
        // Really, this is just a test that we defined NodeVisitor#visit(Ellipsis) to exist...
        fileOptions =
            net.starlark.java.syntax.FileOptions.builder().allowTypeSyntax(true).tolerateInvalidTypeExpressions(true)
                .build()
        assertIdentsAre(
            """
        def a() -> ...:
          pass
        
        """.trimIndent(),
            "a"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun typeOperations() {
        assertIdentsAre(
            """
        cast(a, b)
        isinstance(c, d)
        
        """.trimIndent(),
            "a b c d"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun skipNonSymbolIdentifiers() {
        gathererFactory = java.util.function.Supplier { IdentGatherer.Companion.skippingNonSymbolIdentifiers() }

        assertIdentsAre(
            """
        load("...", a="b")
        c(d=e.f)
        
        """.trimIndent(),  // No b, no d, no f.
            "a c e"
        )
        assertIdentsAre(
            """
        def a(b, c=d):
          pass
        
        """.trimIndent(),  // Keyword param identifiers ("c") are still visited.
            "a b c d"
        )
    }
}
