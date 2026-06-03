// Copyright 2014 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.unix

import com.google.devtools.build.lib.vfs.DigestHashFunction
import org.junit.Test

/** Test for [FileSystem.getFastDigest].  */
class UnixDigestHashAttributeNameTest : FileSystemTest() {
    override fun getFreshFileSystem(digestHashFunction: DigestHashFunction?): FileSystem? {
        return FakeAttributeFileSystem(digestHashFunction)
    }

    @Test
    @Throws(Exception::class)
    fun testFoo() {
        // Instead of actually trying to access this file, a call to getxattr() should be made. We
        // intercept this call and return a fake extended attribute value, thereby causing the checksum
        // computation to be skipped entirely.
        assertThat(DigestUtils.getDigestWithManualFallback(absolutize("myfile"), SyscallCache.NO_CACHE))
            .isEqualTo(FAKE_DIGEST)
    }

    private inner class FakeAttributeFileSystem(hashFunction: DigestHashFunction?) :
        UnixFileSystem(hashFunction, "user.checksum.sha256", NativePosixFilesServiceImpl()) {
        public override fun getxattr(path: PathFragment?, name: String?, followSymlinks: Boolean): ByteArray {
            assertThat(path).isEqualTo(absolutize("myfile").asFragment())
            Truth.assertThat(name).isEqualTo("user.checksum.sha256")
            Truth.assertThat(followSymlinks).isTrue()
            return FAKE_DIGEST
        }
    }

    companion object {
        private val FAKE_DIGEST = byteArrayOf(
            0x18, 0x5f, 0x3d, 0x33, 0x22, 0x71, 0x7e, 0x25,
            0x55, 0x61, 0x26, 0x0c, 0x03, 0x6b, 0x2e, 0x26,
            0x43, 0x06, 0x7c, 0x30, 0x4e, 0x3a, 0x51, 0x20,
            0x07, 0x71, 0x76, 0x48, 0x26, 0x38, 0x19, 0x69,
        )
    }
}
