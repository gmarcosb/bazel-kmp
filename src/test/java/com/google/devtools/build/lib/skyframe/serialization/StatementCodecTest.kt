// Copyright 2022 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.skyframe.serialization

import com.google.devtools.build.lib.skyframe.serialization.testutils.SerializationTester

/** Tests for [Statement_AutoCodec].  */
@RunWith(JUnit4::class)
class StatementCodecTest {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCodec() {
        val stmts: com.google.common.collect.ImmutableList<net.starlark.java.syntax.Statement?> =
            parse(
                "x = y",
                "x += y",
                "5",
                "break",
                "for x in y: print(x)",
                "for x in y: pass",
                "def f(x): print(x)",  // Additional ad hoc assertions for function signature below.
                "def f(a, b='B', *c, d=D, **e): print(x)",
                "def f(a, b='B', *, d=D, **e): print(x)",
                "if x < y: print(x)",
                "elif y < x: print(y)",
                "else: print(z)",
                "load(':foo.bzl', 'A', b='B')",
                "return",
                "return 5",
                "def fib(n):",
                "  if n < 0:",
                "    print('Illegal argument:', n, sep='\t')",
                "  elif n < 2:",
                "    return 1",
                "  else:",
                "    return fib(n - 2) + fib(n - 1)",
                "def cumulative_sum(arr):",
                "  if len(arr) <= 1:",
                "    pass",
                "  else:",
                "    for i in range(1, len(arr)):",
                "      arr[i] += arr[i-1]",
                "      print('Running total is ' + arr[i])",
                "      print('Remaining elements: ' + arr[i+1:])"
            )
        // SerializationTester is dangerously overloaded: the argument must
        // be an ImmutableList or else the Object... overload is selected.
        SerializationTester(stmts)
            .setVerificationFunction(
                { original, deserialized ->
                    Truth.assertThat((deserialized as net.starlark.java.syntax.Statement).prettyPrint())
                        .isEqualTo((original as net.starlark.java.syntax.Statement).prettyPrint())
                })
            .runTests()
    }

    companion object {
        @Throws(net.starlark.java.syntax.SyntaxError.Exception::class)
        private fun parse(vararg lines: String?): com.google.common.collect.ImmutableList<net.starlark.java.syntax.Statement?> {
            val input: net.starlark.java.syntax.ParserInput? = net.starlark.java.syntax.ParserInput.fromLines(lines)
            return net.starlark.java.syntax.StarlarkFile.parse(input).getStatements()
        }
    }
}
