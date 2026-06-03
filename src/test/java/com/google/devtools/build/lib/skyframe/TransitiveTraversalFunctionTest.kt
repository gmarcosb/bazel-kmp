// Copyright 2018 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.skyframe

import com.google.devtools.build.lib.cmdline.Label

/** Test for [TransitiveTraversalFunction].  */
@RunWith(JUnit4::class)
class TransitiveTraversalFunctionTest : BuildViewTestCase() {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun noRepeatedLabelVisitationForTransitiveTraversalFunction() {
        // Create a basic package with a target //foo:foo.
        val label: Label = Label.parseCanonical("//foo:foo")
        scratch.file(
            "foo/BUILD",
            "load('//test_defs:foo_library.bzl', 'foo_library')",
            "foo_library(name = '" + label.name + "')"
        )
        val pkg: Package = loadPackage(label.getPackageIdentifier())
        val targetAndErrorIfAny: TargetAndErrorIfAny =
            TargetAndErrorIfAny( /* packageLoadedSuccessfully= */
                true,  /* errorLoadingTarget= */
                null,
                pkg.getTarget(label.name),
                pkg
            )
        val function: TransitiveTraversalFunction =
            object : TransitiveTraversalFunction() {
                public override fun loadTarget(env: Environment?, label: Label?): TargetAndErrorIfAny {
                    return targetAndErrorIfAny
                }
            }
        // Create the GroupedDeps saying we had already requested two targets the last time we called
        // #compute.
        val groupedDeps: GroupedDeps = GroupedDeps()
        groupedDeps.appendSingleton(label.getPackageIdentifier())
        // Note that these targets don't actually exist in the package we created initially. It doesn't
        // matter for the purpose of this test, the original package was just to create some objects
        // that we needed.
        val fakeDep1: SkyKey? = function.getKey(Label.parseCanonical("//foo:bar"))
        val fakeDep2: SkyKey? = function.getKey(Label.parseCanonical("//foo:baz"))
        groupedDeps.appendGroup(com.google.common.collect.ImmutableList.of<E?>(fakeDep1, fakeDep2))

        val wasOptimizationUsed: AtomicBoolean = AtomicBoolean(false)
        val mockEnv: SkyFunction.Environment =
            Mockito.mock<SkyFunction.Environment>(SkyFunction.Environment::class.java)
        Mockito.`when`<T?>(mockEnv.getTemporaryDirectDeps()).thenReturn(groupedDeps)
        Mockito.`when`<T?>(mockEnv.getValuesAndExceptions(groupedDeps.getDepGroup(1)))
            .thenAnswer(
                Answer { invocationOnMock: InvocationOnMock? ->
                    wasOptimizationUsed.set(true)
                    SimpleSkyframeLookupResult( /* valuesMissingCallback= */
                        java.lang.Runnable {},
                        java.util.function.Function { k: SkyKey? ->
                            throw java.lang.IllegalStateException("Shouldn't have been called: " + k)
                        })
                })
        Mockito.`when`<T?>(mockEnv.valuesMissing()).thenReturn(true)

        // Run the compute function and check that we returned null.
        assertThat(function.compute(function.getKey(label), mockEnv)).isNull()

        // Verify that the mock was called with the arguments we expected.
        Truth.assertThat(wasOptimizationUsed.get()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun multipleErrorsForTransitiveTraversalFunction() {
        val label: Label = Label.parseCanonical("//foo:foo")
        scratch.file(
            "foo/BUILD",
            "load('//test_defs:foo_library.bzl', 'foo_library')",
            "foo_library(name = '" + label.name + "', deps = [':bar', ':baz'])"
        )
        val pkg: Package = loadPackage(label.getPackageIdentifier())
        val targetAndErrorIfAny: TargetAndErrorIfAny =
            TargetAndErrorIfAny( /* packageLoadedSuccessfully= */
                true,  /* errorLoadingTarget= */
                null,
                pkg.getTarget(label.name),
                pkg
            )
        val function: TransitiveTraversalFunction =
            object : TransitiveTraversalFunction() {
                public override fun loadTarget(env: Environment?, label: Label?): TargetAndErrorIfAny {
                    return targetAndErrorIfAny
                }
            }
        val dep1: SkyKey = function.getKey(Label.parseCanonical("//foo:bar"))
        val dep2: SkyKey? = function.getKey(Label.parseCanonical("//foo:baz"))
        val mockEnv: SkyFunction.Environment =
            Mockito.mock<SkyFunction.Environment>(SkyFunction.Environment::class.java)
        val exp1: NoSuchTargetException = NoSuchTargetException("bad bar")
        val exp2: NoSuchTargetException = NoSuchTargetException("bad baz")
        val returnedDeps: SkyframeLookupResult =
            SimpleSkyframeLookupResult(
                java.lang.Runnable {},
                java.util.function.Function { key: SkyKey? ->
                    if (key.equals(dep1))
                        ValueOrUntypedException.ofExn(exp1)
                    else
                        if (key.equals(dep2)) ValueOrUntypedException.ofExn(exp2) else null
                })

        Mockito.`when`<T?>(mockEnv.getValuesAndExceptions(com.google.common.collect.ImmutableSet.of<E?>(dep1, dep2)))
            .thenReturn(returnedDeps)
        Mockito.`when`<T?>(mockEnv.valuesMissing()).thenReturn(false)

        assertThat(
            (function.compute(function.getKey(label), mockEnv) as TransitiveTraversalValue).errorMessage
        )
            .isEqualTo("bad bar")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun selfErrorWins() {
        val label: Label = Label.parseCanonical("//foo:foo")
        scratch.file(
            "foo/BUILD",
            "load('//test_defs:foo_library.bzl', 'foo_library')",
            "foo_library(name = '" + label.name + "', deps = [':bar'])"
        )
        val pkg: Package = loadPackage(label.getPackageIdentifier())
        val targetAndErrorIfAny: TargetAndErrorIfAny =
            TargetAndErrorIfAny( /* packageLoadedSuccessfully= */
                true,  /* errorLoadingTarget= */
                NoSuchTargetException("self error is long and last"),
                pkg.getTarget(label.name),
                pkg
            )
        val function: TransitiveTraversalFunction =
            object : TransitiveTraversalFunction() {
                public override fun loadTarget(env: Environment?, label: Label?): TargetAndErrorIfAny {
                    return targetAndErrorIfAny
                }
            }
        val dep: SkyKey = function.getKey(Label.parseCanonical("//foo:bar"))
        val exp: NoSuchTargetException = NoSuchTargetException("bad bar")
        val returnedDep: SkyframeLookupResult =
            SimpleSkyframeLookupResult(
                java.lang.Runnable {},
                java.util.function.Function { key: SkyKey? -> if (key.equals(dep)) ValueOrUntypedException.ofExn(exp) else null })
        val mockEnv: SkyFunction.Environment =
            Mockito.mock<SkyFunction.Environment>(SkyFunction.Environment::class.java)
        Mockito.`when`<T?>(mockEnv.getValuesAndExceptions(com.google.common.collect.ImmutableSet.of<E?>(dep)))
            .thenReturn(returnedDep)
        Mockito.`when`<T?>(mockEnv.valuesMissing()).thenReturn(false)

        val transitiveTraversalValue: TransitiveTraversalValue =
            function.compute(function.getKey(label), mockEnv) as TransitiveTraversalValue
        assertThat(transitiveTraversalValue.errorMessage).isEqualTo("self error is long and last")
    }

    @get:Throws(java.lang.Exception::class)
    @get:org.junit.Test
    val strictLabelAspectKeys: Unit
        get() {
            val label: Label = Label.parseCanonical("//test:foo")
            scratch.file(
                "test/aspect.bzl",
                """
        def _aspect_impl(target, ctx):
            return []

        def _rule_impl(ctx):
            return []

        MyAspect = aspect(
            implementation = _aspect_impl,
            attr_aspects = ["deps"],
            attrs = {"_extra_deps": attr.label(default = Label("//foo:bar"))},
        )
        my_rule = rule(
            implementation = _rule_impl,
            attrs = {
                "attr": attr.label_list(mandatory = True, aspects = [MyAspect]),
            },
        )
        
        """.trimIndent()
            )
            scratch.file(
                "test/BUILD",
                """
        load("//test:aspect.bzl", "my_rule")

        my_rule(
            name = "foo",
            attr = [":bad"],
        )
        
        """.trimIndent()
            )
            val pkg: Package = loadPackage(label.getPackageIdentifier())
            val targetAndErrorIfAny: TargetAndErrorIfAny =
                TargetAndErrorIfAny( /* packageLoadedSuccessfully= */
                    true,  /* errorLoadingTarget= */
                    null,
                    pkg.getTarget(label.name),
                    pkg
                )
            val function: TransitiveTraversalFunction =
                object : TransitiveTraversalFunction() {
                    public override fun loadTarget(env: Environment?, label: Label?): TargetAndErrorIfAny {
                        return targetAndErrorIfAny
                    }
                }
            val badDep: SkyKey = function.getKey(Label.parseCanonical("//test:bad"))
            val exp: NoSuchTargetException = NoSuchTargetException("bad test")
            val valuesMissing: AtomicBoolean = AtomicBoolean(false)
            val returnedDep: SkyframeLookupResult =
                SimpleSkyframeLookupResult(
                    java.lang.Runnable { valuesMissing.set(true) },
                    java.util.function.Function { key: SkyKey? ->
                        if (key.equals(badDep)) ValueOrUntypedException.ofExn(
                            exp
                        ) else null
                    })
            val mockEnv: SkyFunction.Environment =
                Mockito.mock<SkyFunction.Environment>(SkyFunction.Environment::class.java)
            Mockito.`when`<T?>(mockEnv.getValuesAndExceptions(com.google.common.collect.ImmutableSet.of<E?>(badDep)))
                .thenReturn(returnedDep)

            val transitiveTraversalValue: TransitiveTraversalValue =
                function.compute(function.getKey(label), mockEnv) as TransitiveTraversalValue
            assertThat(transitiveTraversalValue.errorMessage).isEqualTo("bad test")
            Truth.assertThat(valuesMissing.get()).isFalse()
        }

    /* Invokes the loading phase, using Skyframe. */
    @Throws(java.lang.InterruptedException::class, NoSuchPackageException::class)
    private fun loadPackage(pkgid: PackageIdentifier?): Package {
        val result: EvaluationResult<PackageValue?> =
            SkyframeExecutorTestUtils.evaluate<T?>(
                getSkyframeExecutor(), pkgid,  /* keepGoing= */false, reporter
            )
        if (result.hasError()) {
            throw result.getError(pkgid).getException() as NoSuchPackageException?
        }
        return result.get(pkgid).getPackage()
    }
}
