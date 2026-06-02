// Copyright 2020 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.analysis.starlark

import com.google.devtools.build.lib.actions.ParameterFile

/**
 * Checks the result of each variant `Args.add*` call, in flag_per_line format. Note, writes
 * the
 */
@RunWith(JUnit4::class)
class FlagPerLineTest : BuildViewTestCase() {
    // Initially empty, with "flag_per_line" format.
    private var args: Args? = null
    private val mutability: Mutability? = Mutability.create()
    private var thread: StarlarkThread? = null

    @Before
    @Throws(java.lang.Exception::class)
    fun initArgs() {
        args = Args.newArgs(mutability, getStarlarkSemantics())
        args.setParamFileFormat("flag_per_line")
        thread = StarlarkThread.createTransient(mutability, getStarlarkSemantics())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun add_noname() {
        args.addArgument("--foo", Starlark.UNBOUND,  /* format= */Starlark.NONE, thread)
        expectLines("--foo")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun add_name() {
        args.addArgument("--foo", "bar",  /* format= */Starlark.NONE, thread)
        expectLines("--foo=bar")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun add_all_noname() {
        args.addAll( /* argNameOrValue= */
            "",  // ignored
            /* values= */
            StarlarkList.of<T?>(null, "--foo", "bar", "baz"),  /* mapEach= */
            Starlark.NONE,  /* formatEach= */
            Starlark.NONE,  /* beforeEach= */
            Starlark.NONE,  /* omitIfEmpty= */
            true,  // the default
            /* uniquify= */
            false,  /* expandDirectories= */
            false,  /* terminateWith= */
            Starlark.NONE,  /* allowClosure= */
            false,
            thread
        )
        // Absl would reject this line, but it's what we generate.
        expectLines("--foo bar baz")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun add_all_name() {
        args.addAll( /* argNameOrValue= */
            "--foo",  /* values= */
            StarlarkList.of<T?>(null, "bar", "baz"),  /* mapEach= */
            Starlark.NONE,  /* formatEach= */
            Starlark.NONE,  /* beforeEach= */
            Starlark.NONE,  /* omitIfEmpty= */
            true,  // the default
            /* uniquify= */
            false,  /* expandDirectories= */
            false,  /* terminateWith= */
            Starlark.NONE,  /* allowClosure= */
            false,
            thread
        )
        // Absl interprets this as a single value "bar baz" for the flag "--foo",
        // which is probably not what was intended.
        expectLines("--foo=bar baz")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun add_joined_noname() {
        args.addJoined( /* argNameOrValue= */
            "",  // ignored
            /* values= */
            StarlarkList.of<T?>(null, "--foo", "bar", "baz"),  /* joinWith= */
            ",",  /* mapEach= */
            Starlark.NONE,  /* formatEach= */
            Starlark.NONE,  /* formatJoined= */
            Starlark.NONE,  /* omitIfEmpty= */
            true,  // the default
            /* uniquify= */
            false,  /* expandDirectories= */
            false,  /* allowClosure= */
            false,
            thread
        )
        // Absl would reject this line, but it's what we generate.
        expectLines("--foo,bar,baz")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun add_joined_name() {
        args.addJoined( /* argNameOrValue= */
            "--foo",  /* values= */
            StarlarkList.of<T?>(null, "bar", "baz", "woof"),  /* joinWith= */
            ",",  /* mapEach= */
            Starlark.NONE,  /* formatEach= */
            Starlark.NONE,  /* formatJoined= */
            Starlark.NONE,  /* omitIfEmpty= */
            true,  /* uniquify= */
            false,  /* expandDirectories= */
            false,  /* allowClosure= */
            false,
            thread
        )
        expectLines("--foo=bar,baz,woof")
    }

    /** Tests that an add_all (empty and omitted) following two adds works.  */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun args_combinedOmittedAddAllAndAdd() {
        args.addAll( /* argNameOrValue= */
            "",  // ignored
            /* values= */
            StarlarkList.of<T?>(null),  /* mapEach= */
            Starlark.NONE,  /* formatEach= */
            Starlark.NONE,  /* beforeEach= */
            Starlark.NONE,  /* omitIfEmpty= */
            true,  // the default
            /* uniquify= */
            false,  /* expandDirectories= */
            false,  /* terminateWith= */
            Starlark.NONE,  /* allowClosure= */
            false,
            thread
        )
        args.addArgument("--foo1", "bar",  /* format= */Starlark.NONE, thread)
        args.addArgument("--foo2", "bar",  /* format= */Starlark.NONE, thread)

        expectLines("--foo1=bar", "--foo2=bar")
    }

    @Throws(java.lang.Exception::class)
    private fun expectLines(vararg lines: String?) {
        Truth.assertThat(toParamFile(args)).containsExactly(*lines as Array<Any?>?).inOrder()
    }

    companion object {
        /** Writes out the Args using ParameterFile, returns the output broken down as lines.  */
        @Throws(java.lang.Exception::class)
        private fun toParamFile(args: Args): com.google.common.collect.ImmutableList<String?> {
            val bytes: ByteArray
            java.io.ByteArrayOutputStream().use { outputStream ->
                ParameterFile.writeParameterFile(
                    outputStream,
                    args.build({ RepositoryMapping.EMPTY }).arguments(),
                    args.parameterFileType
                )
                bytes = outputStream.toByteArray()
            }
            ByteArrayInputStream(bytes).use { inputStream ->
                java.io.InputStreamReader(inputStream, java.nio.charset.StandardCharsets.UTF_8).use { reader ->
                    return com.google.common.collect.ImmutableList.copyOf<String?>(
                        com.google.common.io.CharStreams.readLines(
                            reader
                        )
                    )
                }
            }
        }
    }
}
