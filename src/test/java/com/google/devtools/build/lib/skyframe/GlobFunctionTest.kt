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
package com.google.devtools.build.lib.skyframe

import com.google.devtools.build.lib.packages.Globber

/** Tests for [GlobFunction].  */
@RunWith(TestParameterInjector::class)
class GlobFunctionTest : GlobTestBase() {
    @TestParameter
    private val recursionInSingleFunction = false

    override fun createGlobSkyFunction(skyFunctions: MutableMap<SkyFunctionName?, SkyFunction?>) {
        skyFunctions.put(SkyFunctions.GLOB, GlobFunction.create(recursionInSingleFunction))
    }

    override fun alwaysUsesDirListing(): Boolean {
        return !recursionInSingleFunction
    }

    @Throws(java.lang.Exception::class)
    override fun assertSingleGlobMatches(
        pattern: String?, globberOperation: Globber.Operation?, vararg expecteds: String?
    ) {
        val matches: Iterable<String?> =
            com.google.common.collect.Iterables.transform<Any?, String?>(
                runSingleGlob(pattern, globberOperation).matches, com.google.common.base.Functions.toStringFunction()
            )
        if (recursionInSingleFunction) {
            Truth.assertThat(matches)
                .containsExactlyElementsIn(com.google.common.collect.ImmutableList.copyOf<String?>(expecteds))
        } else {
            // The order requirement is not strictly necessary -- a change to GlobFunction semantics that
            // changes the output order is fine, but we require that the order be the same here to detect
            // potential non-determinism in output order, which would be bad.
            // The current order in the case of "**" or "*" is roughly that of
            // nestedset.Order.STABLE_ORDER, putting subdirectories before directories, but putting
            // ordinary files after their parent directories.
            Truth.assertThat(matches)
                .containsExactlyElementsIn(com.google.common.collect.ImmutableList.copyOf<String?>(expecteds)).inOrder()
        }
    }

    @Throws(java.lang.Exception::class)
    override fun runSingleGlob(pattern: String?, globberOperation: Globber.Operation?): GlobValue? {
        val skyKey: SkyKey =
            GlobValue.key(
                GlobTestBase.Companion.PKG_ID,
                Root.fromPath(root),
                pattern,
                globberOperation,
                PathFragment.EMPTY_FRAGMENT
            )
        val result: EvaluationResult<SkyValue?> =
            evaluator.evaluate(
                com.google.common.collect.ImmutableList.of<E?>(skyKey),
                GlobTestBase.Companion.EVALUATION_OPTIONS
            )
        if (result.hasError()) {
            throw result.getError().getException()
        }
        return result.get(skyKey) as GlobValue?
    }

    override fun assertIllegalPattern(pattern: String?) {
        org.junit.Assert.assertThrows<T?>(
            "invalid pattern not detected: " + pattern,
            InvalidGlobPatternException::class.java,
            org.junit.function.ThrowingRunnable {
                GlobValue.key(
                    GlobTestBase.Companion.PKG_ID,
                    Root.fromPath(root),
                    pattern,
                    Globber.Operation.FILES_AND_DIRS,
                    PathFragment.EMPTY_FRAGMENT
                )
            })
    }

    @Throws(InvalidGlobPatternException::class)
    override fun createdGlobRelatedSkyKey(
        pattern: String?, globberOperation: Globber.Operation?
    ): GlobDescriptor {
        return GlobValue.key(
            GlobTestBase.Companion.PKG_ID, Root.fromPath(root), pattern, globberOperation, PathFragment.EMPTY_FRAGMENT
        )
    }

    @Throws(java.lang.Exception::class)
    override fun getSubpackagesMatches(pattern: String?): Iterable<String?> {
        return com.google.common.collect.Iterables.transform<Any?, String?>(
            runSingleGlob(pattern, Globber.Operation.SUBPACKAGES).matches,
            com.google.common.base.Functions.toStringFunction()
        )
    }
}
