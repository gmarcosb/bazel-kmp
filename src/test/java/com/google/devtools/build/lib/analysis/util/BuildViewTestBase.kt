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
package com.google.devtools.build.lib.analysis.util


import com.google.common.eventbus.Subscribe
import com.google.devtools.build.lib.actions.Artifact
import com.google.devtools.build.skyframe.NotifyingHelper
import java.util.regex.Pattern
import kotlin.collections.ArrayList
import kotlin.collections.MutableList

/**
 * Base class for BuildView test cases.
 */
abstract class BuildViewTestBase : AnalysisTestCase() {
    @Throws(Exception::class)
    protected fun setupDummyRule() {
        scratch.file(
            "pkg/BUILD",
            """
        testing_dummy_rule(
            name = "foo",
            srcs = ["a.src"],
            outs = ["a.out"],
        )
        
        """.trimIndent()
        )
    }

    @Throws(Exception::class)
    protected fun runAnalysisWithOutputFilter(outputFilter: Pattern?) {
        scratch.file(
            "java/a/BUILD",
            "exports_files(['A.java'])"
        )
        scratch.file(
            "java/b/BUILD",
            """
        load("@rules_java//java:defs.bzl", "java_library")
        java_library(name = 'b', srcs = ['//java/a:A.java'])
        
        """.trimIndent()
        )
        scratch.file(
            "java/c/BUILD",
            """
        load("@rules_java//java:defs.bzl", "java_library")
        java_library(name = 'c', exports = ['//java/b:b'])
        
        """.trimIndent()
        )
        reporter.setOutputFilter(RegexOutputFilter.forPattern(outputFilter))
        update("//java/c:c")
    }

    @Throws(Exception::class)
    protected fun getNativeDepsLibrary(target: ConfiguredTarget): Artifact {
        return ActionsTestUtil.getFirstArtifactEndingWith(
            target
                .getProvider(RunfilesProvider::class.java)
                .getDefaultRunfiles()
                .getAllArtifacts(), "_swigdeps.so"
        )
    }

    @Throws(Exception::class)
    protected fun runTestDepOnGoodTargetInBadPkgAndTransitiveCycle(incremental: Boolean) {
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        scratch.file(
            "parent/BUILD",
            """
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(
            name = "foo",
            srcs = [
                "//badpkg:okay-target",
                "//okaypkg:transitively-a-cycle",
            ],
        )
        
        """.trimIndent()
        )
        val symlinkcycleBuildFile: Path =
            scratch.file(
                "symlinkcycle/BUILD",
                "load('//test_defs:foo_library.bzl', 'foo_library')",
                "foo_library(name = 'cycle', srcs = glob(['*.sh']))"
            )
        val dirPath: Path = symlinkcycleBuildFile.getParentDirectory()
        dirPath.getRelative("foo.sh").createSymbolicLink(PathFragment.create("foo.sh"))
        scratch.file(
            "okaypkg/BUILD",
            """
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(
            name = "transitively-a-cycle",
            srcs = ["//symlinkcycle:cycle"],
        )
        
        """.trimIndent()
        )
        val badpkgBuildFile: Path =
            scratch.file(
                "badpkg/BUILD",
                """
            exports_files(["okay-target"])

            fail()
            
            """.trimIndent()
            )
        if (incremental) {
            update(defaultFlags().with(Flag.KEEP_GOING), "//okaypkg:transitively-a-cycle")
            assertContainsEvent("circular symlinks detected")
            eventCollector.clear()
        }
        update(defaultFlags().with(Flag.KEEP_GOING), "//parent:foo")
        // Each event string may contain stack traces and error messages with multiple file names.
        assertContainsEventWithFrequency(badpkgBuildFile.asFragment().getPathString(), 1)
        // TODO(nharmata): This test currently only works because each BuildViewTest#update call
        // dirties all FileNodes that are in error. There is actually a skyframe bug with cycle
        // reporting on incremental builds (see b/14622820).
        assertContainsEvent("circular symlinks detected")
    }

    protected fun injectGraphListenerForTesting(listener: NotifyingHelper.Listener?, deterministic: Boolean) {
        val memoizingEvaluator: InMemoryMemoizingEvaluator =
            skyframeExecutor.getEvaluator() as InMemoryMemoizingEvaluator
        memoizingEvaluator.injectGraphTransformerForTesting(
            DeterministicHelper.makeTransformer(listener, deterministic)
        )
    }

    /**
     * Record analysis failures.
     */
    class AnalysisFailureRecorder {
        @Subscribe
        fun analysisFailure(event: AnalysisFailureEvent?) {
            events.add(event)
        }

        @Subscribe
        fun analysisFailureCause(event: AnalysisRootCauseEvent?) {
            causes.add(event)
        }

        val events: MutableList<AnalysisFailureEvent?> = ArrayList<AnalysisFailureEvent?>()
        val causes: MutableList<AnalysisRootCauseEvent?> = ArrayList<AnalysisRootCauseEvent?>()
    }

    /**
     * Record loading failures.
     */
    class LoadingFailureRecorder {
        @Subscribe
        fun loadingFailure(event: LoadingFailureEvent?) {
            events.add(event)
        }

        val events: MutableList<LoadingFailureEvent?> = ArrayList<LoadingFailureEvent?>()
    }
}
