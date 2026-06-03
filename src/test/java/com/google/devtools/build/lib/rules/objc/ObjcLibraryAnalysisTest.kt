// Copyright 2021 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.rules.objc

import com.google.common.collect.ImmutableList
import com.google.devtools.build.lib.actions.Action
import org.junit.Test

/** Tests for `objc_library` that require performing analysis.  */
@RunWith(JUnit4::class)
class ObjcLibraryAnalysisTest : AnalysisTestCase() {
    @Before
    @Throws(Exception::class)
    fun setup() {
        MockObjcSupport.setup(mockToolsConfig)
        useConfiguration(*MockObjcSupport.requiredObjcCrosstoolFlags().toTypedArray<String?>())
    }

    @Test
    @Throws(Exception::class)
    fun libraryToLinkStaysInSyncWithConfiguredTarget() {
        val builds: MutableList<Pair<String?, String?>> =
            ImmutableList.of<E>(
                Pair.of("clean build", "['a.m']"),
                Pair.of("action added", "['a.m', 'b.m']"),
                Pair.of("action removed", "['a.m']")
            )

        for (build in builds) {
            val context: String? = build.first
            val srcs: String? = build.second

            scratch.overwriteFile(
                "foo/BUILD",
                "load('@rules_cc//cc:objc_library.bzl', 'objc_library')",
                "objc_library(name = 'lib', srcs = " + srcs + ")"
            )
            update("//foo:lib")

            val libraryToLink: DerivedArtifact =
                CcInfo.get(getConfiguredTarget("//foo:lib"))
                    .getCcLinkingContext()
                    .getLibraries()
                    .getSingleton()
                    .getStaticLibrary() as DerivedArtifact

            val generatingActionKey: ActionLookupData = libraryToLink.getGeneratingActionKey()
            val actionLookupValue: ActionLookupValue =
                skyframeExecutor
                    .getEvaluator()
                    .getExistingValue(generatingActionKey.getActionLookupKey()) as ActionLookupValue
            val generatingAction: Action = actionLookupValue.getAction(generatingActionKey.getActionIndex())

            Truth.assertWithMessage(context).that(generatingAction.getPrimaryOutput()).isEqualTo(libraryToLink)
        }
    }
}
