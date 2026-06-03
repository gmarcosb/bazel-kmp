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

import com.google.devtools.build.lib.skyframe.BzlLoadValue.keyForBuild

/** Tests for [ModuleCodec].  */
@RunWith(JUnit4::class)
class ModuleCodecTest : BuildViewTestCase() {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDynamicCodec() {
        val subject1: net.starlark.java.eval.Module? = net.starlark.java.eval.Module.create()

        val subject2: net.starlark.java.eval.Module =
            net.starlark.java.eval.Module.withPredeclaredAndData(
                StarlarkSemantics.DEFAULT,
                com.google.common.collect.ImmutableMap.of<String?, Any?>(),
                Label.parseCanonical("//foo:bar")
            )
        subject2.setGlobal("x", 1)
        subject2.setGlobal("y", 2)

        val subject3: net.starlark.java.eval.Module =
            net.starlark.java.eval.Module.withPredeclaredAndData(
                StarlarkSemantics.DEFAULT,
                com.google.common.collect.ImmutableMap.of<String?, Any?>(),
                Label.parseCanonical("//foo:bar")
            )
        subject3.setGlobal("x", 1, net.starlark.java.syntax.Types.INT)
        subject3.setGlobal("y", 2, net.starlark.java.syntax.Types.ANY)

        SerializationTester(subject1, subject2, subject3)
            .makeMemoizing()
            .setVerificationFunction({ subject: net.starlark.java.eval.Module?, deserialized: net.starlark.java.eval.Module? ->
                verifyDeserialization(
                    subject,
                    deserialized
                )
            })
            .runTestsWithoutStableSerializationCheck()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCodec() {
        scratch.file("lib/BUILD")
        scratch.file(
            "pkg/foo.bzl",
            """
        def _impl(ctx):
            print("xyz is %s" % ctx.attr.xyz)
        my_rule = rule(
            implementation=_impl,
            attrs = {
              "xyz": attr.string(),
            },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "pkg/BUILD",
            """
        load(":foo.bzl", "my_rule")
        my_rule(
            name = "abc",
            xyz = "value",
        )
        
        """.trimIndent()
        )

        // Evaluates pkg to populate pkg/foo.bzl in Skyframe.
        assertThat(getPackage("pkg")).isNotNull()

        // Pulls the module value out of Skyframe from its BzlLoadValue.
        val bzlLoadKey: BzlLoadValue.Key? = keyForBuild(Label.parseCanonical("//pkg:foo.bzl"))
        val fooBzl: BzlLoadValue = getDoneValue(bzlLoadKey) as BzlLoadValue
        val module: net.starlark.java.eval.Module? = fooBzl.getModule()

        val deserialized: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            RoundTripping.roundTripWithSkyframe(
                ObjectCodecs().withCodecOverridesForTesting(com.google.common.collect.ImmutableList.of<E?>(moduleCodec())),
                FingerprintValueService.createForTesting(),
                { key: SkyKey? -> this.getDoneValue(key) },
                module
            )

        assertThat(deserialized).isSameInstanceAs(module)
    }

    @Throws(java.lang.InterruptedException::class)
    private fun getPackage(pkgName: String?): Package? {
        try {
            return packageManager.getPackage(reporter, PackageIdentifier.createInMainRepo(pkgName))
        } catch (unused: NoSuchPackageException) {
            return null
        }
    }

    private fun getDoneValue(key: SkyKey?): SkyValue {
        try {
            return skyframeExecutor.getDoneSkyValueForIntrospection(key)
        } catch (e: SkyframeExecutor.FailureToRetrieveIntrospectedValueException) {
            throw java.lang.AssertionError(e)
        }
    }

    companion object {
        private fun verifyDeserialization(
            subject: net.starlark.java.eval.Module?,
            deserialized: net.starlark.java.eval.Module?
        ) {
            // Module doesn't implement proper equality.
            TestUtils.assertModulesEqual(subject, deserialized)
        }
    }
}
