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
package com.google.devtools.build.lib.buildtool.util

import com.google.common.collect.ImmutableList
import com.google.common.collect.Iterables
import com.google.devtools.build.lib.skyframe.SkyframeExecutor
import java.lang.ref.WeakReference
import java.nio.charset.StandardCharsets
import java.util.*

/** Infrastructure to support Skyframe integration tests.  */
abstract class SkyframeIntegrationTestBase : BuildIntegrationTestCase() {
    protected fun skyframeExecutor(): SkyframeExecutor? {
        return runtimeWrapper.getSkyframeExecutor()
    }

    private fun makeGenruleContents(value: String?): String? {
        return String.format(
            "genrule(name='target', outs=['out'], cmd='/bin/echo %s > $(location out)')", value
        )
    }

    @Throws(Exception::class)
    protected fun writeGenrule(filename: String?, value: String?) {
        write(filename, makeGenruleContents(value))
    }

    @Throws(Exception::class)
    protected fun writeGenruleAbsolute(file: Path, value: String?) {
        writeAbsolute(file, makeGenruleContents(value))
    }

    @Throws(Exception::class)
    protected fun assertCharContentsIgnoringOrderAndWhitespace(
        expectedCharContents: String, target: String?
    ) {
        val path: Path? = Iterables.getOnlyElement<Artifact?>(getArtifacts(target)).getPath()
        val actualChars: CharArray = FileSystemUtils.readContentAsLatin1(path)
        val expectedChars: CharArray = expectedCharContents.toCharArray()
        Arrays.sort(actualChars)
        Arrays.sort(expectedChars)
        Truth.assertThat(String(actualChars).trim { it <= ' ' }).isEqualTo(String(expectedChars).trim { it <= ' ' })
    }

    @Throws(Exception::class)
    protected fun getOnlyOutputContentAsLines(target: String?): ImmutableList<String?> {
        return FileSystemUtils.readLines(
            Iterables.getOnlyElement<Artifact?>(getArtifacts(target)).getPath(), StandardCharsets.UTF_8
        )
    }

    companion object {
        @Throws(Exception::class)
        protected fun weakRefs(vararg strongRefs: Any?): MutableList<WeakReference<*>?> {
            val result: MutableList<WeakReference<*>?> = ArrayList<WeakReference<*>?>()
            for (ref in strongRefs) {
                result.add(WeakReference<Any?>(ref))
            }
            return result
        }

        protected fun assertAllReleased(refs: Iterable<WeakReference<*>>) {
            for (ref in refs) {
                GcFinalization.awaitClear(ref)
            }
        }
    }
}
