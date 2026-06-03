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
package com.google.devtools.build.lib.starlarkbuildapi.core

import com.google.common.collect.ImmutableSet
import com.google.devtools.build.lib.cmdline.BazelCompileContext
import org.junit.Test
import java.util.*

@RunWith(JUnit4::class)
class ContextGuardedValueTest {
    /**
     * We want to make sure the empty string doesn't result in "allow everything". That would be bad.
     * Most allowlists have an entry like ("", "tools/build_defs/lang") for usage within Google.
     */
    @Test
    @Throws(Exception::class)
    fun emptyRepoAllowed_doesntMatchNonAllowed() {
        assertNotAllowed("@mylang//bar:baz", "@//tools/lang")
    }

    @Test
    @Throws(Exception::class)
    fun emptyRepoAllowed_matchesAllowed() {
        assertAllowed("@//tools/lang", "@//tools/lang")
    }

    @Test
    @Throws(Exception::class)
    fun workspaceRepo_matchesAllowedRepo() {
        assertAllowed("@rules_foo//tools/lang", "@rules_foo//")
    }

    @Test
    @Throws(Exception::class)
    fun workspaceRepo_doesntMatchCommonSubstr() {
        assertNotAllowed("@my_rules_foo_helper//tools/lang", "@rules_foo//")
    }

    @Test
    @Throws(Exception::class)
    fun bzlmodRepo_matchesStart() {
        assertAllowed("@rules_foo+override//tools/lang", "@rules_foo//")
        assertAllowed("@rules_foo+1.2.3//tools/lang", "@rules_foo//")
    }

    @Test
    @Throws(Exception::class)
    fun bzlmodRepo_matchesWithin() {
        assertAllowed("@rules_lang+override+ext+foo_helper//tools/lang", "@foo_helper//")
    }

    @Test
    @Throws(Exception::class)
    fun bzlmodRepo_doesntMatchCommonSubstr() {
        assertNotAllowed("@rules_lang+override+ext+my_foo_helper_lib//tools/lang", "@foo_helper//")
    }

    @Test
    @Throws(Exception::class)
    fun reposWithDotsDontMatch() {
        assertNotAllowed("@my.lang//foo", "@my_lang//")
    }

    @Test
    @Throws(Exception::class)
    fun verifySomeRealisticCases() {
        // Python with workspace
        assertAllowed("@//tools/build_defs/python/private", "@//tools/build_defs/python")
        assertAllowed("@rules_python//python/private", "@rules_python//")

        // Python with bzlmod
        assertAllowed(
            "@rules_python+override+internal_deps+rules_python_internal//private", "@rules_python//"
        )

        // CC with workspace
        assertAllowed("@//tools/build_defs/cc", "@//tools/build_defs/cc")
        assertNotAllowed("@rules_cc_helper//tools/build_defs/cc", "@rules_cc//")

        // CC with Bzlmod
        assertAllowed("@rules_cc+1.2.3+ext_name+local_cc_config//foo", "@local_cc_config//")
    }

    private fun createClientData(callerLabelStr: String?): Any {
        return BazelCompileContext.create(
            Label.parseCanonicalUnchecked(callerLabelStr), "unused_caller.bzl"
        )
    }

    @Throws(Exception::class)
    private fun createGuard(clientData: Any, vararg allowedLabelStrs: String?): GuardedValue {
        val allowed =
            Arrays.stream<String?>(allowedLabelStrs)
                .map<Any?> { labelStr: String? ->
                    try {
                        return@map PackageIdentifier.parse(labelStr)
                    } catch (e: LabelSyntaxException) {
                        // We have to manually catch and re-throw this, otherwise Java is unhappy.
                        throw RuntimeException(e)
                    }
                }
                .collect(ImmutableSet.toImmutableSet<Any?>())

        return ContextGuardedValue.onlyInAllowedRepos(clientData, allowed)
    }

    @Throws(Exception::class)
    private fun assertAllowed(callerLabelStr: String?, vararg allowedLabelStrs: String?) {
        val clientData = createClientData(callerLabelStr)
        val guard: GuardedValue = createGuard(clientData, *allowedLabelStrs)
        assertThat(guard.isObjectAccessibleUsingSemantics(StarlarkSemantics.DEFAULT, clientData))
            .isTrue()
    }

    @Throws(Exception::class)
    private fun assertNotAllowed(callerLabelStr: String?, vararg allowedLabelStrs: String?) {
        val clientData = createClientData(callerLabelStr)
        val guard: GuardedValue = createGuard(clientData, *allowedLabelStrs)
        assertThat(guard.isObjectAccessibleUsingSemantics(StarlarkSemantics.DEFAULT, clientData))
            .isFalse()
    }
}
