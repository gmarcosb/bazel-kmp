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
package com.google.devtools.build.lib.exec

import com.google.devtools.build.lib.actions.ActionInput

/** Tests SingleBuildFileCache.  */
@RunWith(JUnit4::class)
class SingleBuildFileCacheTest {
    private var fs: FileSystem? = null
    private var calls: MutableMap<String?, Int?>? = null
    private var digestOverrides: MutableMap<String?, ByteArray?>? = null

    private var underTest: SingleBuildFileCache? = null

    @Before
    @Throws(java.lang.Exception::class)
    fun setUp() {
        calls = HashMap<String?, Int?>()
        digestOverrides = HashMap<String?, ByteArray?>()
        fs =
            object : InMemoryFileSystem(DigestHashFunction.SHA256) {
                @Throws(IOException::class)
                public override fun getInputStream(path: PathFragment): java.io.InputStream? {
                    var c: Int = (if (calls.containsKey(path.toString())) calls.get(path.toString()) else 0)!!
                    c++
                    calls!!.put(path.toString(), c)
                    return super.getInputStream(path)
                }

                @Throws(IOException::class)
                public override fun getDigest(path: PathFragment): ByteArray? {
                    val override = digestOverrides!!.get(path.getPathString())
                    return if (override != null) override else super.getDigest(path)
                }

                @Throws(IOException::class)
                public override fun getFastDigest(path: PathFragment?): ByteArray? {
                    return null
                }
            }
        underTest =
            SingleBuildFileCache(
                "/", PathFragment.create("dummy-output-path"), fs, SyscallCache.NO_CACHE
            )
        FileSystemUtils.createEmptyFile(fs.getPath("/empty"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNonExistentPath() {
        val empty: ActionInput? = ActionInputHelper.fromPath("/noexist")
        org.junit.Assert.assertThrows<IOException?>(
            "non existent file should raise exception",
            IOException::class.java,
            org.junit.function.ThrowingRunnable { underTest.getInputMetadata(empty) })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDirectory() {
        val file: Path = fs.getPath("/directory")
        file.createDirectory()
        val input: ActionInput? = ActionInputHelper.fromPath(file.getPathString())
        val expected: DigestOfDirectoryException? =
            org.junit.Assert.assertThrows<T?>(
                "directory should raise exception",
                DigestOfDirectoryException::class.java,
                org.junit.function.ThrowingRunnable { underTest.getInputMetadata(input) })
        assertThat(expected).hasMessageThat().isEqualTo("Input is a directory: /directory")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCache() {
        val empty: ActionInput? = ActionInputHelper.fromPath("/empty")
        underTest.getInputMetadata(empty).getDigest()
        Truth.assertThat(calls).containsKey("/empty")
        Truth.assertThat(calls!!.get("/empty") as Int).isEqualTo(1)
        underTest.getInputMetadata(empty).getDigest()
        Truth.assertThat(calls!!.get("/empty") as Int).isEqualTo(1)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBasic() {
        val empty: ActionInput? = ActionInputHelper.fromPath("/empty")
        assertThat(underTest.getInputMetadata(empty).getSize()).isEqualTo(0)
        val digest: ByteArray? = underTest.getInputMetadata(empty).getDigest()
        val expected: ByteArray? = fs.getDigestFunction().getHashFunction().hashBytes(ByteArray(0)).asBytes()
        Truth.assertThat(digest).isEqualTo(expected)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testUnreadableFileWhenFileSystemSupportsDigest() {
        val expectedDigest: ByteArray? = "expected".toByteArray(java.nio.charset.StandardCharsets.UTF_8)
        digestOverrides!!.put("/unreadable", expectedDigest)

        val input: ActionInput? = ActionInputHelper.fromPath("/unreadable")
        val file: Path = fs.getPath("/unreadable")
        FileSystemUtils.createEmptyFile(file)
        file.chmod(0)
        val actualDigest: ByteArray? = underTest.getInputMetadata(input).getDigest()
        Truth.assertThat(actualDigest).isEqualTo(expectedDigest)
    }
}
