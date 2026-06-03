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
package com.google.devtools.build.skyframe

import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec

/** Unit test for the SkyKey class, checking hash code transience logic.  */
@RunWith(JUnit4::class)
class SkyKeyTest {
    @org.junit.Test
    @Throws(java.lang.Exception::class)  // Testing interactions with spy.
    fun testHashCodeTransience() {
        // Given a freshly constructed HashCodeSpy object,
        val hashCodeSpy = HashCodeSpy()
        Truth.assertThat(hashCodeSpy.numberOfTimesHashCodeCalled).isEqualTo(0)

        // When a SkyKey is constructed with that HashCodeSpy as its argument,
        val originalKey: SkyKey = HashCodeSpyKey(hashCodeSpy)

        // Then the HashCodeSpy reports that its hashcode method was called once.
        Truth.assertThat(hashCodeSpy.numberOfTimesHashCodeCalled).isEqualTo(1)

        // When the SkyKey's hashCode method is called,
        originalKey.hashCode()

        // Then the spy's hashCode method isn't called, because the SkyKey's hashCode was cached.
        Truth.assertThat(hashCodeSpy.numberOfTimesHashCodeCalled).isEqualTo(1)

        // When that SkyKey is serialized and then deserialized,
        val newKey: SkyKey = RoundTripping.roundTrip(originalKey)

        // Then the new SkyKey recomputed its hashcode on deserialization.
        assertThat(newKey.hashCode()).isEqualTo(originalKey.hashCode())
        val spyInNewKey = newKey.argument() as HashCodeSpy
        Truth.assertThat(spyInNewKey.numberOfTimesHashCodeCalled).isEqualTo(1)

        // When the new SkyKey's hashCode method is called,
        newKey.hashCode()

        // Then the new SkyKey's spy's hashCode method is not called again.
        Truth.assertThat(spyInNewKey.numberOfTimesHashCodeCalled).isEqualTo(1)
    }

    internal class HashCodeSpy {
        @Transient
        private var numberOfTimesHashCodeCalled = 0

        override fun hashCode(): Int {
            numberOfTimesHashCodeCalled++
            return 42
        }

        // Implemented so that numberOfTimesHashCodeCalled is not incremented when the debugger calls
        // toString() - the default Object#toString() calls hashCode().
        override fun toString(): String {
            return String.format("HashCodeSpy{count=%s}", numberOfTimesHashCodeCalled)
        }
    }

    @AutoCodec
    internal class HashCodeSpyKey(arg: HashCodeSpy?) : AbstractSkyKey.WithCachedHashCode<HashCodeSpy?>(arg) {
        public override fun functionName(): SkyFunctionName {
            return SkyFunctionName.FOR_TESTING
        }
    }
}
