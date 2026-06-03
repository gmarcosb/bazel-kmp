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
package com.google.devtools.build.lib.cmdline

import com.google.devtools.build.lib.cmdline.LabelValidator.PackageAndTarget

/**
 * Tests for [LabelValidator].
 */
@RunWith(JUnit4::class)
class LabelValidatorTest {
    private fun newFooTarget(): PackageAndTarget {
        return PackageAndTarget("foo", "foo")
    }

    private fun newBarTarget(): PackageAndTarget {
        return PackageAndTarget("bar", "bar")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testValidatePackageName() {
        // OK:
        assertThat(LabelValidator.validatePackageName("foo")).isNull()
        assertThat(LabelValidator.validatePackageName("Foo")).isNull()
        assertThat(LabelValidator.validatePackageName("FOO")).isNull()
        assertThat(LabelValidator.validatePackageName("foO")).isNull()
        assertThat(LabelValidator.validatePackageName("foo-bar")).isNull()
        assertThat(LabelValidator.validatePackageName("Foo-Bar")).isNull()
        assertThat(LabelValidator.validatePackageName("FOO-BAR")).isNull()
        assertThat(LabelValidator.validatePackageName("bar.baz")).isNull()
        assertThat(LabelValidator.validatePackageName("a/..b")).isNull()
        assertThat(LabelValidator.validatePackageName("a/.b")).isNull()
        assertThat(LabelValidator.validatePackageName("a/b.")).isNull()
        assertThat(LabelValidator.validatePackageName("a/b..")).isNull()
        assertThat(LabelValidator.validatePackageName("a$( )/b..")).isNull()

        // These are in ascii code order.
        assertThat(LabelValidator.validatePackageName("foo!bar")).isNull()
        assertThat(LabelValidator.validatePackageName("foo\"bar")).isNull()
        assertThat(LabelValidator.validatePackageName("foo#bar")).isNull()
        assertThat(LabelValidator.validatePackageName("foo\$bar")).isNull()
        assertThat(LabelValidator.validatePackageName("foo%bar")).isNull()
        assertThat(LabelValidator.validatePackageName("foo&bar")).isNull()
        assertThat(LabelValidator.validatePackageName("foo'bar")).isNull()
        assertThat(LabelValidator.validatePackageName("foo(bar")).isNull()
        assertThat(LabelValidator.validatePackageName("foo)bar")).isNull()
        assertThat(LabelValidator.validatePackageName("foo*bar")).isNull()
        assertThat(LabelValidator.validatePackageName("foo+bar")).isNull()
        assertThat(LabelValidator.validatePackageName("foo,bar")).isNull()
        assertThat(LabelValidator.validatePackageName("foo-bar")).isNull()
        assertThat(LabelValidator.validatePackageName("foo.bar")).isNull()
        assertThat(LabelValidator.validatePackageName("foo+bar")).isNull()
        assertThat(LabelValidator.validatePackageName("foo;bar")).isNull()
        assertThat(LabelValidator.validatePackageName("foo<bar")).isNull()
        assertThat(LabelValidator.validatePackageName("foo=bar")).isNull()
        assertThat(LabelValidator.validatePackageName("foo>bar")).isNull()
        assertThat(LabelValidator.validatePackageName("foo?bar")).isNull()
        assertThat(LabelValidator.validatePackageName("foo@bar")).isNull()
        assertThat(LabelValidator.validatePackageName("foo[bar")).isNull()
        assertThat(LabelValidator.validatePackageName("foo]bar")).isNull()
        assertThat(LabelValidator.validatePackageName("foo^bar")).isNull()
        assertThat(LabelValidator.validatePackageName("foo_bar")).isNull()
        assertThat(LabelValidator.validatePackageName("foo`bar")).isNull()
        assertThat(LabelValidator.validatePackageName("foo{bar")).isNull()
        assertThat(LabelValidator.validatePackageName("foo|bar")).isNull()
        assertThat(LabelValidator.validatePackageName("foo}bar")).isNull()
        assertThat(LabelValidator.validatePackageName("foo~bar")).isNull()

        // Bad:
        assertThat(LabelValidator.validatePackageName("/foo"))
            .isEqualTo("package names may not start with '/'")
        assertThat(LabelValidator.validatePackageName("foo/"))
            .isEqualTo("package names may not end with '/'")
        assertThat(LabelValidator.validatePackageName("foo:bar"))
            .isEqualTo(LabelValidator.PACKAGE_NAME_ERROR)

        assertThat(LabelValidator.validatePackageName("bar/../baz"))
            .isEqualTo(LabelValidator.PACKAGE_NAME_DOT_ERROR)
        assertThat(LabelValidator.validatePackageName("bar/.."))
            .isEqualTo(LabelValidator.PACKAGE_NAME_DOT_ERROR)
        assertThat(LabelValidator.validatePackageName("../bar"))
            .isEqualTo(LabelValidator.PACKAGE_NAME_DOT_ERROR)
        assertThat(LabelValidator.validatePackageName("bar/..."))
            .isEqualTo(LabelValidator.PACKAGE_NAME_DOT_ERROR)

        assertThat(LabelValidator.validatePackageName("bar/./baz"))
            .isEqualTo(LabelValidator.PACKAGE_NAME_DOT_ERROR)
        assertThat(LabelValidator.validatePackageName("bar/."))
            .isEqualTo(LabelValidator.PACKAGE_NAME_DOT_ERROR)
        assertThat(LabelValidator.validatePackageName("./bar"))
            .isEqualTo(LabelValidator.PACKAGE_NAME_DOT_ERROR)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testValidateTargetName() {
        assertThat(LabelValidator.validateTargetName("foo")).isNull()
        assertThat(LabelValidator.validateTargetName("foo!bar")).isNull()
        assertThat(LabelValidator.validateTargetName("foo\"bar")).isNull()
        assertThat(LabelValidator.validateTargetName("foo#bar")).isNull()
        assertThat(LabelValidator.validateTargetName("foo\$bar")).isNull()
        assertThat(LabelValidator.validateTargetName("foo%bar")).isNull()
        assertThat(LabelValidator.validateTargetName("foo&bar")).isNull()
        assertThat(LabelValidator.validateTargetName("foo'bar")).isNull()
        assertThat(LabelValidator.validateTargetName("foo(bar")).isNull()
        assertThat(LabelValidator.validateTargetName("foo)bar")).isNull()
        assertThat(LabelValidator.validateTargetName("foo*bar")).isNull()
        assertThat(LabelValidator.validateTargetName("foo+bar")).isNull()
        assertThat(LabelValidator.validateTargetName("foo,bar")).isNull()
        assertThat(LabelValidator.validateTargetName("foo-bar")).isNull()
        assertThat(LabelValidator.validateTargetName("foo.bar")).isNull()
        assertThat(LabelValidator.validateTargetName("foo+bar")).isNull()
        assertThat(LabelValidator.validateTargetName("foo;bar")).isNull()
        assertThat(LabelValidator.validateTargetName("foo<bar")).isNull()
        assertThat(LabelValidator.validateTargetName("foo=bar")).isNull()
        assertThat(LabelValidator.validateTargetName("foo>bar")).isNull()
        assertThat(LabelValidator.validateTargetName("foo?bar")).isNull()
        assertThat(LabelValidator.validateTargetName("foo[bar")).isNull()
        assertThat(LabelValidator.validateTargetName("foo]bar")).isNull()
        assertThat(LabelValidator.validateTargetName("foo^bar")).isNull()
        assertThat(LabelValidator.validateTargetName("foo_bar")).isNull()
        assertThat(LabelValidator.validateTargetName("foo`bar")).isNull()
        assertThat(LabelValidator.validateTargetName("foo{bar")).isNull()
        assertThat(LabelValidator.validateTargetName("foo|bar")).isNull()
        assertThat(LabelValidator.validateTargetName("foo}bar")).isNull()
        assertThat(LabelValidator.validateTargetName("foo~bar")).isNull()

        assertThat(LabelValidator.validateTargetName("foo/bar")).isNull()
        assertThat(LabelValidator.validateTargetName("foo@bar")).isNull()

        assertThat(LabelValidator.validateTargetName("foo/"))
            .isEqualTo("target names may not end with '/'")
        assertThat(LabelValidator.validateTargetName("bar:baz"))
            .isEqualTo("target names may not contain ':'")
        assertThat(LabelValidator.validateTargetName("bar:"))
            .isEqualTo("target names may not contain ':'")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testValidateAbsoluteLabel() {
        var emptyPackage: PackageAndTarget = PackageAndTarget("", "bar")
        assertThat(LabelValidator.validateAbsoluteLabel("//:bar")).isEqualTo(emptyPackage)
        assertThat(LabelValidator.validateAbsoluteLabel("@repo//:bar")).isEqualTo(emptyPackage)
        assertThat(LabelValidator.validateAbsoluteLabel("@repo//foo:bar"))
            .isEqualTo(PackageAndTarget("foo", "bar"))
        assertThat(LabelValidator.validateAbsoluteLabel("@//foo:bar"))
            .isEqualTo(PackageAndTarget("foo", "bar"))
        emptyPackage = PackageAndTarget("", "b$() ar")
        assertThat(LabelValidator.validateAbsoluteLabel("//:b$() ar")).isEqualTo(emptyPackage)
        assertThat(LabelValidator.validateAbsoluteLabel("@repo//:b$() ar")).isEqualTo(emptyPackage)
        assertThat(LabelValidator.validateAbsoluteLabel("@repo//f$( )oo:b$() ar"))
            .isEqualTo(PackageAndTarget("f$( )oo", "b$() ar"))
        assertThat(LabelValidator.validateAbsoluteLabel("@//f$( )oo:b$() ar"))
            .isEqualTo(PackageAndTarget("f$( )oo", "b$() ar"))
        assertThat(LabelValidator.validateAbsoluteLabel("//f@oo"))
            .isEqualTo(PackageAndTarget("f@oo", "f@oo"))
        assertThat(LabelValidator.validateAbsoluteLabel("//@foo"))
            .isEqualTo(PackageAndTarget("@foo", "@foo"))
        assertThat(LabelValidator.validateAbsoluteLabel("//@foo:@bar"))
            .isEqualTo(PackageAndTarget("@foo", "@bar"))
    }

    @org.junit.Test
    fun testPackageAndTargetHashCode_distinctButEqualObjects() {
        val fooTarget1: PackageAndTarget = newFooTarget()
        val fooTarget2: PackageAndTarget = newFooTarget()
        assertThat(fooTarget2).isNotSameInstanceAs(fooTarget1)
        Truth.assertWithMessage("Should have same hash code")
            .that(fooTarget1.hashCode())
            .isEqualTo(fooTarget2.hashCode())
    }

    @org.junit.Test
    fun testPackageAndTargetEquals_distinctButEqualObjects() {
        val fooTarget1: PackageAndTarget = newFooTarget()
        val fooTarget2: PackageAndTarget = newFooTarget()
        assertThat(fooTarget2).isNotSameInstanceAs(fooTarget1)
        Truth.assertWithMessage("Should be equal").that(fooTarget1).isEqualTo(fooTarget2)
    }

    @org.junit.Test
    fun testPackageAndTargetEquals_unequalObjects() {
        Truth.assertWithMessage("should be unequal").that(newFooTarget().equals(newBarTarget())).isFalse()
    }

    @org.junit.Test
    fun testPackageAndTargetToString() {
        assertThat(newFooTarget().toString()).isEqualTo("//foo:foo")
        assertThat(newBarTarget().toString()).isEqualTo("//bar:bar")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSlashlessLabel_infersTargetNameFromRepoName() {
        assertThat(LabelValidator.parseAbsoluteLabel("@foo").toString()).isEqualTo("//:foo")
    }
}
