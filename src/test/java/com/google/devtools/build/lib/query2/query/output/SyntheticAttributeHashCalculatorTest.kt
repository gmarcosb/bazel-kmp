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
package com.google.devtools.build.lib.query2.query.output

import com.google.common.collect.ImmutableMap
import com.google.devtools.build.lib.packages.Attribute
import org.junit.Test

/** Tests for [SyntheticAttributeHashCalculator].  */
@RunWith(TestParameterInjector::class)
class SyntheticAttributeHashCalculatorTest : PackageLoadingTestCase() {
    @Test
    @Throws(Exception::class)
    fun testComputeAttributeChangeChangesHash() {
        scratch.file("pkg/BUILD", "genrule(name='x', cmd='touch $@', outs=['y'])")
        val ruleBefore: Rule? = getTarget("//pkg:x") as Rule?

        scratch.overwriteFile("pkg/BUILD", "genrule(name='x', cmd='touch $@', outs=['z'])")
        invalidatePackages()
        val ruleAfter: Rule? = getTarget("//pkg:x") as Rule?

        val hashBefore =
            SyntheticAttributeHashCalculator.compute(
                ruleBefore,  /* serializedAttributes= */
                ImmutableMap.of<Attribute, Build.Attribute>(),  /* extraDataForAttrHash= */
                "",
                DigestHashFunction.SHA256.getHashFunction(),  /* includeAttributeSourceAspects= */
                false,  /* includeStarlarkRuleEnv= */
                true
            )
        val hashAfter =
            SyntheticAttributeHashCalculator.compute(
                ruleAfter,  /* serializedAttributes= */
                ImmutableMap.of<Attribute, Build.Attribute>(),  /* extraDataForAttrHash= */
                "",
                DigestHashFunction.SHA256.getHashFunction(),  /* includeAttributeSourceAspects= */
                false,  /* includeStarlarkRuleEnv= */
                true
            )

        Truth.assertThat(hashBefore).isNotEqualTo(hashAfter)
    }

    @Test
    @Throws(Exception::class)
    fun testComputeLocationDoesntChangeHash() {
        scratch.file("pkg/BUILD", "genrule(name='x', cmd='touch $@', outs=['y'])")
        val ruleBefore: Rule? = getTarget("//pkg:x") as Rule?

        scratch.overwriteFile(
            "pkg/BUILD",
            """
        genrule(
            name = "rule_that_moves_x",
            outs = ["whatever"],
            cmd = "touch ${'$'}@",
        )

        genrule(
            name = "x",
            outs = ["y"],
            cmd = "touch ${'$'}@",
        )
        
        """.trimIndent()
        )
        invalidatePackages()
        val ruleAfter: Rule? = getTarget("//pkg:x") as Rule?

        val hashBefore =
            SyntheticAttributeHashCalculator.compute(
                ruleBefore,  /* serializedAttributes= */
                ImmutableMap.of<Attribute, Build.Attribute>(),  /* extraDataForAttrHash= */
                "",
                DigestHashFunction.SHA256.getHashFunction(),  /* includeAttributeSourceAspects= */
                false,  /* includeStarlarkRuleEnv= */
                true
            )
        val hashAfter =
            SyntheticAttributeHashCalculator.compute(
                ruleAfter,  /* serializedAttributes= */
                ImmutableMap.of<Attribute, Build.Attribute>(),  /* extraDataForAttrHash= */
                "",
                DigestHashFunction.SHA256.getHashFunction(),  /* includeAttributeSourceAspects= */
                false,  /* includeStarlarkRuleEnv= */
                true
            )

        Truth.assertThat(hashBefore).isEqualTo(hashAfter)
    }

    @Test
    @Throws(Exception::class)
    fun testComputeSerializedAttributesUsedOverAvailable() {
        scratch.file("pkg/BUILD", "genrule(name='x', cmd='touch $@', outs=['y'])")
        val rule: Rule? = getTarget("//pkg:x") as Rule?

        val hashBefore =
            SyntheticAttributeHashCalculator.compute(
                rule,  /* serializedAttributes= */
                ImmutableMap.of<Attribute, Build.Attribute>(),  /* extraDataForAttrHash= */
                "",
                DigestHashFunction.SHA256.getHashFunction(),  /* includeAttributeSourceAspects= */
                false,  /* includeStarlarkRuleEnv= */
                true
            )

        val serializedAttributes: ImmutableMap<Attribute?, Build.Attribute?> =
            ImmutableMap.of<K?, V?>(
                rule.getRuleClassObject().getAttributeProvider().getAttributeByName("cmd"),
                Build.Attribute.newBuilder()
                    .setName("dummy")
                    .setType(Discriminator.STRING)
                    .setStringValue("hi")
                    .build()
            )

        val hashAfter =
            SyntheticAttributeHashCalculator.compute(
                rule,
                serializedAttributes,  /*extraDataForAttrHash*/
                "",
                DigestHashFunction.SHA256.getHashFunction(),  /* includeAttributeSourceAspects= */
                false,  /* includeStarlarkRuleEnv= */
                true
            )

        Truth.assertThat(hashBefore).isNotEqualTo(hashAfter)
    }

    @Test
    @Throws(Exception::class)
    fun testComputeExtraDataChangesHash() {
        scratch.file("pkg/BUILD", "genrule(name='x', cmd='touch $@', outs=['y'])")
        val rule: Rule? = getTarget("//pkg:x") as Rule?

        val hashBefore =
            SyntheticAttributeHashCalculator.compute(
                rule,  /* serializedAttributes= */
                ImmutableMap.of<Attribute, Build.Attribute>(),  /* extraDataForAttrHash= */
                "",
                DigestHashFunction.SHA256.getHashFunction(),  /* includeAttributeSourceAspects= */
                false,  /* includeStarlarkRuleEnv= */
                true
            )

        val hashAfter =
            SyntheticAttributeHashCalculator.compute(
                rule,  /* serializedAttributes= */
                ImmutableMap.of<Attribute, Build.Attribute>(),  /*extraDataForAttrHash*/
                "blahblaah",
                DigestHashFunction.SHA256.getHashFunction(),  /* includeAttributeSourceAspects= */
                false,  /* includeStarlarkRuleEnv= */
                true
            )

        Truth.assertThat(hashBefore).isNotEqualTo(hashAfter)
    }

    @Test
    @Throws(Exception::class)
    fun testComputePackageErrorStatusChangesHash() {
        scratch.file("pkg/BUILD", "genrule(name='x', cmd='touch $@', outs=['y'])")
        val ruleBefore: Rule? = getTarget("//pkg:x") as Rule?

        // Remove fail-fast handler, we're intentionally creating a package with errors.
        reporter.removeHandler(failFastHandler)
        scratch.overwriteFile(
            "pkg/BUILD",
            """
        genrule(
            name = "x",
            outs = ["z"],
            cmd = "touch ${'$'}@",
        )

        genrule(name = "missing_attributes")
        
        """.trimIndent()
        )
        invalidatePackages()
        val ruleAfter: Rule? = getTarget("//pkg:x") as Rule?
        assertThat(ruleAfter.containsErrors()).isTrue()

        val hashBefore =
            SyntheticAttributeHashCalculator.compute(
                ruleBefore,  /* serializedAttributes= */
                ImmutableMap.of<Attribute, Build.Attribute>(),  /* extraDataForAttrHash= */
                "",
                DigestHashFunction.SHA256.getHashFunction(),  /* includeAttributeSourceAspects= */
                false,  /* includeStarlarkRuleEnv= */
                true
            )
        val hashAfter =
            SyntheticAttributeHashCalculator.compute(
                ruleAfter,  /* serializedAttributes= */
                ImmutableMap.of<Attribute, Build.Attribute>(),  /* extraDataForAttrHash= */
                "",
                DigestHashFunction.SHA256.getHashFunction(),  /* includeAttributeSourceAspects= */
                false,  /* includeStarlarkRuleEnv= */
                true
            )

        Truth.assertThat(hashBefore).isNotEqualTo(hashAfter)
    }

    @Test
    @Throws(Exception::class)
    fun testComputeIncludeAttributeSourceAspectsChangesHash() {
        scratch.file(
            "a/defs.bzl",
            """
        def _test_aspect_impl(target, ctx):
            return []

        test_aspect = aspect(
            implementation = _test_aspect_impl,
            attr_aspects = ["deps"],
            attrs = {
                "_aspect_attr_1": attr.label(default = "//a:c"),
                "_aspect_attr_2": attr.label(default = "//a:d"),
            },
        )

        def _lib_impl(ctx):
            return

        test_lib = rule(
            implementation = _lib_impl,
            attrs = {
                "deps": attr.label_list(aspects = [test_aspect]),
            },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "a/BUILD",
            """
        load("defs.bzl", "test_lib")

        test_lib(
            name = "a",
            deps = [":b"],
        )

        test_lib(name = "b")
        
        """.trimIndent()
        )
        val rule: Rule? = getTarget("//a:a") as Rule?

        val hashWithAttributeAspects =
            SyntheticAttributeHashCalculator.compute(
                rule,  /* serializedAttributes= */
                ImmutableMap.of<Attribute, Build.Attribute>(),  /* extraDataForAttrHash= */
                "",
                DigestHashFunction.SHA256.getHashFunction(),  /* includeAttributeSourceAspects= */
                true,  /* includeStarlarkRuleEnv= */
                true
            )

        val hashWithoutAttributeAspects =
            SyntheticAttributeHashCalculator.compute(
                rule,  /* serializedAttributes= */
                ImmutableMap.of<Attribute, Build.Attribute>(),  /* extraDataForAttrHash= */
                "",
                DigestHashFunction.SHA256.getHashFunction(),  /* includeAttributeSourceAspects= */
                false,  /* includeStarlarkRuleEnv= */
                true
            )
        Truth.assertThat(hashWithAttributeAspects).isNotEqualTo(hashWithoutAttributeAspects)
    }

    @Test
    @Throws(Exception::class)
    fun testStarlarkRuleEnvChanges(@TestParameter includeStarlarkRuleEnv: Boolean) {
        scratch.file(
            "a/defs.bzl",
            """
        def _lib_impl(ctx):
            return "old"

        test_lib = rule(
            implementation = _lib_impl,
        )
        
        """.trimIndent()
        )
        scratch.file(
            "a/BUILD",
            """
        load("defs.bzl", "test_lib")

        test_lib(name = "a")
        
        """.trimIndent()
        )
        val ruleBefore: Rule? = getTarget("//a:a") as Rule?

        scratch.overwriteFile(
            "a/defs.bzl",
            """
        def _lib_impl(ctx):
            return "new"

        test_lib = rule(
            implementation = _lib_impl,
        )
        
        """.trimIndent()
        )

        invalidatePackages()
        val ruleAfter: Rule? = getTarget("//a:a") as Rule?

        val hashBefore =
            SyntheticAttributeHashCalculator.compute(
                ruleBefore,  /* serializedAttributes= */
                ImmutableMap.of<Attribute, Build.Attribute>(),  /* extraDataForAttrHash= */
                "",
                DigestHashFunction.SHA256.getHashFunction(),  /* includeAttributeSourceAspects= */
                false,
                includeStarlarkRuleEnv
            )

        val hashAfter =
            SyntheticAttributeHashCalculator.compute(
                ruleAfter,  /* serializedAttributes= */
                ImmutableMap.of<Attribute, Build.Attribute>(),  /* extraDataForAttrHash= */
                "",
                DigestHashFunction.SHA256.getHashFunction(),  /* includeAttributeSourceAspects= */
                false,
                includeStarlarkRuleEnv
            )
        if (includeStarlarkRuleEnv) {
            Truth.assertThat(hashBefore).isNotEqualTo(hashAfter)
        } else {
            Truth.assertThat(hashBefore).isEqualTo(hashAfter)
        }
    }
}
