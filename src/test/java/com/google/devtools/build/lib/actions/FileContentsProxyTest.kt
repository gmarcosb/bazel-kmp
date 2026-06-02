// Copyright 2017 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.actions

import com.google.devtools.build.lib.util.Fingerprint

/** Tests for [FileContentsProxy].  */
@RunWith(JUnit4::class)
class FileContentsProxyTest {
    /** A simple implementation of FileStatus for testing.  */
    private class InjectedStat : FileStatus {
        private val mtime: Long
        private val ctime: Long
        private val size: Long
        private val nodeId: Long

        internal constructor(mtime: Long, ctime: Long, size: Long, nodeId: Long) {
            this.mtime = mtime
            this.ctime = ctime
            this.size = size
            this.nodeId = nodeId
        }

        internal constructor(ctime: Long, nodeId: Long) {
            this.ctime = ctime
            this.mtime = ctime
            this.nodeId = nodeId
            this.size = 0
        }

        public override fun isFile(): Boolean {
            return true
        }

        public override fun isSpecialFile(): Boolean {
            return false
        }

        public override fun isDirectory(): Boolean {
            return false
        }

        public override fun isSymbolicLink(): Boolean {
            return false
        }

        public override fun getSize(): Long {
            return size
        }

        public override fun getLastModifiedTime(): Long {
            return mtime
        }

        public override fun getLastChangeTime(): Long {
            return ctime
        }

        public override fun getNodeId(): Long {
            return nodeId
        }
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun equalsAndHashCode() {
        EqualsTester()
            .addEqualityGroup(
                FileContentsProxy.create(InjectedStat(1L, 2L)),
                FileContentsProxy.create(InjectedStat(1L, 2L))
            )
            .addEqualityGroup(FileContentsProxy.create(InjectedStat(1L, 4L)))
            .addEqualityGroup(FileContentsProxy.create(InjectedStat(3L, 4L)))
            .addEqualityGroup(FileContentsProxy.create(InjectedStat(-1L, -1L)))
            .testEquals()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun fingerprint() {
        val p1: FileContentsProxy =
            FileContentsProxy.create(
                InjectedStat( /*mtime=*/1,  /*ctime=*/2,  /*size=*/3,  /*nodeId=*/4)
            )
        val fingerprint: Fingerprint = Fingerprint()
        p1.addToFingerprint(fingerprint)
        assertThat(fingerprint.digestAndReset())
            .isEqualTo(Fingerprint().addLong(2L).addLong(1L).addLong(4L).digestAndReset())
    }
}
