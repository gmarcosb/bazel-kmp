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
package com.google.devtools.build.lib.rules.apple

import com.google.common.testing.EqualsTester
import com.google.common.truth.Truth
import com.google.devtools.build.lib.rules.apple.DottedVersion.InvalidDottedVersionException
import org.junit.Assert
import org.junit.Test
import org.junit.function.ThrowingRunnable
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * Tests for [DottedVersion].
 */
@RunWith(JUnit4::class)
class DottedVersionTest {
    @Test
    @Throws(Exception::class)
    fun testCompareTo() {
        ComparatorTester()
            .addEqualityGroup(DottedVersion.fromString("0"), DottedVersion.fromString("0.0.0"))
            .addEqualityGroup(DottedVersion.fromString("0.1"), DottedVersion.fromString("0.01"))
            .addEqualityGroup(DottedVersion.fromString("0.2"), DottedVersion.fromString("0.2.0"))
            .addEqualityGroup(DottedVersion.fromString("0.2.1"))
            .addEqualityGroup(DottedVersion.fromString("1.2alpha"))
            .addEqualityGroup(DottedVersion.fromString("1.2alpha1"))
            .addEqualityGroup(DottedVersion.fromString("1.2alpha2"))
            .addEqualityGroup(DottedVersion.fromString("1.2beta1"))
            .addEqualityGroup(DottedVersion.fromString("1.2beta12"))
            .addEqualityGroup(DottedVersion.fromString("1.2beta12.1"))
            .addEqualityGroup(DottedVersion.fromString("1.2.0"), DottedVersion.fromString("1.2"))
            .addEqualityGroup(DottedVersion.fromString("1.20"))
            .addEqualityGroup(DottedVersion.fromString("10"), DottedVersion.fromString("10.0"))
            .addEqualityGroup(DottedVersion.fromString("10.0.0.10A255"))
            .addEqualityGroup(DottedVersion.fromString("10.2"))
            .addEqualityGroup(DottedVersion.fromString("10.2.0.10P99q"))
            .addEqualityGroup(
                DottedVersion.fromString("10.10.simulator.internal"), DottedVersion.fromString("10.10")
            )
            .testCompare()
    }

    @Test
    @Throws(Exception::class)
    fun testEquals() {
        EqualsTester()
            .addEqualityGroup(DottedVersion.fromString("0"), DottedVersion.fromString("0.0.0"))
            .addEqualityGroup(DottedVersion.fromString("0.1"), DottedVersion.fromString("0.01"))
            .addEqualityGroup(DottedVersion.fromString("0.2"), DottedVersion.fromString("0.2.0"))
            .addEqualityGroup(DottedVersion.fromString("1.2xy2"), DottedVersion.fromString("1.2xy2"))
            .addEqualityGroup(
                DottedVersion.fromString("10.2.0.10P99q"), DottedVersion.fromString("10.2.0.10P99q0")
            )
            .addEqualityGroup(
                DottedVersion.fromString("1.2x"),
                DottedVersion.fromString("1.2x0"),
                DottedVersion.fromString("1.2x0.0")
            )
            .testEquals()
    }

    @Test
    @Throws(Exception::class)
    fun testToStringWithComponents() {
        val dottedVersion = DottedVersion.fromString("42.8")
        Truth.assertThat(dottedVersion.toStringWithComponents(1)).isEqualTo("42")
        Truth.assertThat(dottedVersion.toStringWithComponents(2)).isEqualTo("42.8")
        Truth.assertThat(dottedVersion.toStringWithComponents(3)).isEqualTo("42.8.0")
        Truth.assertThat(dottedVersion.toStringWithComponents(4)).isEqualTo("42.8.0.0")
    }

    @Test
    @Throws(Exception::class)
    fun testToStringWithComponents_trailingZero() {
        val dottedVersion = DottedVersion.fromString("4.3alpha3.0")
        Truth.assertThat(dottedVersion.toStringWithComponents(1)).isEqualTo("4")
        Truth.assertThat(dottedVersion.toStringWithComponents(2)).isEqualTo("4.3alpha3")
        Truth.assertThat(dottedVersion.toStringWithComponents(3)).isEqualTo("4.3alpha3.0")
        Truth.assertThat(dottedVersion.toStringWithComponents(4)).isEqualTo("4.3alpha3.0.0")
        Truth.assertThat(dottedVersion.toStringWithComponents(5)).isEqualTo("4.3alpha3.0.0.0")
    }

    @Test
    @Throws(Exception::class)
    fun testToStringWithComponents_zeroComponent() {
        val zeroComponent = DottedVersion.fromString("0")
        Truth.assertThat(zeroComponent.toStringWithComponents(1)).isEqualTo("0")
        Truth.assertThat(zeroComponent.toStringWithComponents(2)).isEqualTo("0.0")
        Truth.assertThat(zeroComponent.toStringWithComponents(3)).isEqualTo("0.0.0")
    }

    @Test
    @Throws(Exception::class)
    fun testToStringWithMinimumComponent() {
        val dottedVersion = DottedVersion.fromString("42.8")
        Truth.assertThat(dottedVersion.toStringWithMinimumComponents(0)).isEqualTo("42.8")
        Truth.assertThat(dottedVersion.toStringWithMinimumComponents(1)).isEqualTo("42.8")
        Truth.assertThat(dottedVersion.toStringWithMinimumComponents(2)).isEqualTo("42.8")
        Truth.assertThat(dottedVersion.toStringWithMinimumComponents(3)).isEqualTo("42.8.0")
        Truth.assertThat(dottedVersion.toStringWithMinimumComponents(4)).isEqualTo("42.8.0.0")
    }

    @Test
    @Throws(Exception::class)
    fun testToStringWithMinimumComponent_trailingZero() {
        val dottedVersion = DottedVersion.fromString("4.3alpha3.0")
        Truth.assertThat(dottedVersion.toStringWithMinimumComponents(0)).isEqualTo("4.3alpha3.0")
        Truth.assertThat(dottedVersion.toStringWithMinimumComponents(1)).isEqualTo("4.3alpha3.0")
        Truth.assertThat(dottedVersion.toStringWithMinimumComponents(2)).isEqualTo("4.3alpha3.0")
        Truth.assertThat(dottedVersion.toStringWithMinimumComponents(3)).isEqualTo("4.3alpha3.0")
        Truth.assertThat(dottedVersion.toStringWithMinimumComponents(4)).isEqualTo("4.3alpha3.0.0")
        Truth.assertThat(dottedVersion.toStringWithMinimumComponents(5)).isEqualTo("4.3alpha3.0.0.0")
    }

    @Test
    @Throws(Exception::class)
    fun testToStringWithMinimumComponents_zeroComponent() {
        val zeroComponent = DottedVersion.fromString("0")
        Truth.assertThat(zeroComponent.toStringWithMinimumComponents(0)).isEqualTo("0")
        Truth.assertThat(zeroComponent.toStringWithMinimumComponents(1)).isEqualTo("0")
        Truth.assertThat(zeroComponent.toStringWithMinimumComponents(2)).isEqualTo("0.0")
        Truth.assertThat(zeroComponent.toStringWithMinimumComponents(3)).isEqualTo("0.0.0")
    }

    @Test
    @Throws(Exception::class)
    fun testIllegalVersion_noLeadingInteger() {
        val expected: Throwable? =
            Assert.assertThrows<InvalidDottedVersionException?>(
                InvalidDottedVersionException::class.java, ThrowingRunnable { DottedVersion.fromString("a") })
        Truth.assertThat(expected).hasMessageThat().contains("a")
    }

    @Test
    @Throws(Exception::class)
    fun testIllegalVersion_empty() {
        Assert.assertThrows<InvalidDottedVersionException?>(
            InvalidDottedVersionException::class.java, ThrowingRunnable { DottedVersion.fromString("") })
    }

    @Test
    @Throws(Exception::class)
    fun testIllegalVersion_punctuation() {
        Assert.assertThrows<InvalidDottedVersionException?>(
            InvalidDottedVersionException::class.java, ThrowingRunnable { DottedVersion.fromString("2:3") })
    }

    @Test
    @Throws(Exception::class)
    fun testIllegalVersion_emptyComponent() {
        Assert.assertThrows<InvalidDottedVersionException?>(
            InvalidDottedVersionException::class.java, ThrowingRunnable { DottedVersion.fromString("1..3") })
    }

    @Test
    @Throws(Exception::class)
    fun testIllegalVersion_negativeComponent() {
        Assert.assertThrows<InvalidDottedVersionException?>(
            InvalidDottedVersionException::class.java, ThrowingRunnable { DottedVersion.fromString("1.-1") })
    }
}
