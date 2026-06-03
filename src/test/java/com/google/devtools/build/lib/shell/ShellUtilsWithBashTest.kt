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
package com.google.devtools.build.lib.shell

import com.google.devtools.build.lib.shell.ShellUtils.prettyPrintArgv

/** Tests for ShellUtils that call out to Bash.  */
@RunWith(JUnit4::class)
class ShellUtilsWithBashTest {
    @Throws(java.lang.Exception::class)
    private fun assertTokenizeIsDualToPrettyPrint(vararg args: String?) {
        val `in`: MutableList<String?> = java.util.Arrays.asList<String?>(*args)
        val shellCommand: String? = prettyPrintArgv(`in`)

        // Assert that pretty-print is correct, i.e. dual to the actual /bin/sh
        // tokenization.  This test assumes no newlines in the input:
        val execArgs: com.google.common.collect.ImmutableList<String?> =
            com.google.common.collect.ImmutableList.of<String?>(
                "/bin/sh",
                "-c",
                "for i in " + shellCommand + "; do echo \"\$i\"; done" // tokenize, one word per line
            )
        var stdout: String? = null
        try {
            stdout = String(Command(execArgs, java.lang.System.getenv()).execute().getStdout())
        } catch (e: java.lang.Exception) {
            org.junit.Assert.fail("/bin/sh failed:\n" + `in` + "\n" + shellCommand + "\n" + e.message)
        }
        // We can't use stdout.split("\n") here,
        // because String.split() ignores trailing empty strings.
        val words: java.util.ArrayList<String?> = java.util.ArrayList<String?>()
        var index: Int
        while ((stdout.indexOf('\n').also { index = it }) >= 0) {
            words.add(stdout.substring(0, index))
            stdout = stdout.substring(index + 1)
        }
        Truth.assertThat(words).isEqualTo(`in`)

        // Assert that tokenize is dual to pretty-print:
        val out: MutableList<String?> = java.util.ArrayList<String?>()
        try {
            tokenize(out, shellCommand)
        } finally {
            if (out.isEmpty()) { // i.e. an exception
                java.lang.System.err.println(`in`)
            }
        }
        Truth.assertThat(out).isEqualTo(`in`)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTokenizeIsDualToPrettyPrint() {
        // tokenize() is the inverse of prettyPrintArgv().  (However, the reverse
        // is not true, since there are many ways to escape the same string,
        // e.g. "foo" and 'foo'.)

        assertTokenizeIsDualToPrettyPrint("foo")
        assertTokenizeIsDualToPrettyPrint("foo bar")
        assertTokenizeIsDualToPrettyPrint("foo bar", "wiz")
        assertTokenizeIsDualToPrettyPrint("'foo'")
        assertTokenizeIsDualToPrettyPrint("\\'foo\\'")
        assertTokenizeIsDualToPrettyPrint("\${filename%.c}.o")
        assertTokenizeIsDualToPrettyPrint("<html!>")

        assertTokenizeIsDualToPrettyPrint("")
        assertTokenizeIsDualToPrettyPrint("!@#$%^&*()")
        assertTokenizeIsDualToPrettyPrint("x'y\" z")
    }
}
