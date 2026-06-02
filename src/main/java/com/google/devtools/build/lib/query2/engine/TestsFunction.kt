// Copyright 2014 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.query2.engine

import com.google.common.annotations.VisibleForTesting
import com.google.common.base.Function
import com.google.common.collect.ImmutableList
import com.google.common.collect.Iterables
import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe
import java.util.function.Consumer
import kotlin.collections.ArrayList
import kotlin.collections.Iterable
import kotlin.collections.MutableCollection
import kotlin.collections.MutableList
import kotlin.collections.MutableSet

/**
 * A tests(x) filter expression, which returns all the tests in set x, expanding test_suite rules
 * into their constituents.
 * 
 * 
 * Unfortunately this class reproduces a substantial amount of logic from `TestSuiteConfiguredTarget`, albeit in a somewhat simplified form. This is basically inevitable
 * since the expansion of test_suites cannot be done during the loading phase, because it involves
 * inter-package references. We make no attempt to validate the input, or report errors or warnings
 * other than missing target.
 * 
 * <pre>expr ::= TESTS '(' expr ')'</pre>
 */
class TestsFunction @VisibleForTesting constructor() : QueryFunction {
    val name: String
        get() = "tests"

    val mandatoryArguments: Int
        get() = 1

    val argumentTypes: MutableList<QueryEnvironment.ArgumentType?>
        get() = ImmutableList.of<QueryEnvironment.ArgumentType?>(
            QueryEnvironment.ArgumentType.EXPRESSION
        )

    override fun <T> eval(
        env: QueryEnvironment<T?>,
        context: QueryExpressionContext<T?>?,
        expression: QueryExpression?,
        args: MutableList<QueryEnvironment.Argument?>,
        callback: Callback<T?>
    ): QueryTaskFuture<Void?>? {
        val closure = Closure<T?>(expression, callback, env)

        // A callback that appropriately feeds top-level test and test_suite targets to 'closure'.
        val visitAllTestSuitesCallback =
            Callback { partialResult: Iterable<T?>? ->
                val partitionResult = closure.partition(partialResult!!)
                closure
                    .getUniqueTestSuites(partitionResult.testSuiteTargets)
                    .forEach(Consumer { testSuite: T? -> closure.visitUniqueTestsInUniqueSuite(testSuite) })
                callback.process(closure.getUniqueTests(partitionResult.testTargets))
            }

        // Get a future that represents full evaluation of the argument expression.
        val testSuiteVisitationStartedFuture: QueryTaskFuture<Void?>? =
            env.eval(args.get(0)!!.getExpression(), context, visitAllTestSuitesCallback)

        return env.transformAsync<Void?, Void?>( // When this future is done, all top-level test_suite targets have already been fed to the
            // 'closure', meaning that ...
            testSuiteVisitationStartedFuture,  // ... 'closure.getTopLevelRecursiveVisitationFutures()' represents the full visitation of
            // all these test_suite targets.
            Function { dummyVal: Void? -> env.whenAllSucceed(closure.getTopLevelRecursiveVisitationFutures()) })
    }

    private class PartitionResult<T>(
        val testTargets: ImmutableList<T?>,
        val testSuiteTargets: ImmutableList<T?>?,
        val otherTargets: ImmutableList<T?>
    )

    /** A closure over the state needed to do asynchronous test_suite visitation and expansion.  */
    @ThreadSafe
    private class Closure<T>(
        private val expression: QueryExpression?, private val callback: Callback<T?>,
        /** The environment in which this query is being evaluated.  */
        private val env: QueryEnvironment<T?>
    ) {
        private val accessor: TargetAccessor<T?>
        private val strict: Boolean
        private val testUniquifier: Uniquifier<T?>
        private val testSuiteUniquifier: Uniquifier<T?>
        private val topLevelRecursiveVisitationFutures: MutableList<QueryTaskFuture<Void?>?> =
            Collections.synchronizedList<QueryTaskFuture<Void?>?>(
                ArrayList<QueryTaskFuture<Void?>?>()
            )

        init {
            this.accessor = env.getAccessor()
            this.strict = env.isSettingEnabled(QueryEnvironment.Setting.TESTS_EXPRESSION_STRICT)
            this.testUniquifier = env.createUniquifier()
            this.testSuiteUniquifier = env.createUniquifier()
        }

        @Throws(QueryException::class)
        fun getUniqueTests(tests: Iterable<T?>?): Iterable<T?> {
            return testUniquifier.unique(tests)
        }

        @Throws(QueryException::class)
        fun getUniqueTestSuites(testSuites: Iterable<T?>?): Iterable<T?> {
            return testSuiteUniquifier.unique(testSuites)
        }

        fun visitUniqueTestsInUniqueSuite(testSuite: T?) {
            topLevelRecursiveVisitationFutures.add(
                env.executeAsync<Void?>(QueryTaskAsyncCallable { recursivelyVisitUniqueTestsInUniqueSuite(testSuite) })
            )
        }

        /**
         * Returns all the futures representing the work items entailed by all the previous calls to
         * [.visitUniqueTestsInUniqueSuite].
         */
        fun getTopLevelRecursiveVisitationFutures(): ImmutableList<QueryTaskFuture<Void?>?> {
            return ImmutableList.copyOf<QueryTaskFuture<Void?>?>(topLevelRecursiveVisitationFutures)
        }

        fun recursivelyVisitUniqueTestsInUniqueSuite(testSuite: T?): QueryTaskFuture<Void?>? {
            val tagsAttribute = accessor.getStringListAttr(testSuite, "tags")
            // Split the tags list into positive and negative tags
            val requiredTags: MutableSet<String?> = HashSet<String?>()
            val excludedTags: MutableSet<String?> = HashSet<String?>()
            sortTagsBySense(tagsAttribute, requiredTags, excludedTags)

            val testsToProcess: MutableList<T?> = ArrayList<T?>()
            val testSuites: MutableList<T?>

            try {
                val partitionResult = partition(getPrerequisites(testSuite, "tests")!!)

                for (testTarget in partitionResult.testTargets) {
                    if (includeTest(requiredTags, excludedTags, testTarget)
                        && testUniquifier.unique(testTarget)
                    ) {
                        testsToProcess.add(testTarget)
                    }
                }

                testSuites = testSuiteUniquifier.unique(partitionResult.testSuiteTargets)

                // If strict mode is enabled, then give an error for any non-test, non-test-suite target.
                if (strict) {
                    for (otherTarget in partitionResult.otherTargets) {
                        val message: String? = String.format(
                            "The label '%s' in the test_suite '%s' does not refer to a test or test_suite"
                                    + " rule!",
                            accessor.getLabel(otherTarget), accessor.getLabel(testSuite)
                        )
                        env.handleError(
                            expression,
                            message,
                            DetailedExitCode.of(
                                FailureDetail.newBuilder()
                                    .setMessage(message)
                                    .setQuery(Query.newBuilder().setCode(Code.INVALID_LABEL_IN_TEST_SUITE))
                                    .build()
                            )
                        )
                    }
                }

                // Add implicit dependencies on tests in same package, if any.
                for (target in getPrerequisites(testSuite, "\$implicit_tests")!!) {
                    // The Package construction of $implicit_tests ensures that this check never fails, but we
                    // add it here anyway for compatibility with future code.
                    if (accessor.isTestRule(target)
                        && includeTest(requiredTags, excludedTags, target)
                        && testUniquifier.unique(target)
                    ) {
                        testsToProcess.add(target)
                    }
                }
            } catch (e: InterruptedException) {
                return env.immediateCancelledFuture<Void?>()
            } catch (e: QueryException) {
                return env.immediateFailedFuture<Void?>(e)
            }

            // Process all tests, asynchronously.
            val allTestsProcessedFuture: QueryTaskFuture<Void?>? =
                env.execute<Void?>(
                    QueryTaskCallable {
                        callback.process(testsToProcess)
                        null
                    })

            // Visit all suites recursively, asynchronously.
            val allTestSuitsVisitedFuture: QueryTaskFuture<Void?>? =
                env.whenAllSucceed(
                    Iterables.transform<T?, QueryTaskFuture<Void?>?>(
                        testSuites,
                        Function { testSuite: T? -> this.recursivelyVisitUniqueTestsInUniqueSuite(testSuite) })
                )

            return env.whenAllSucceed(
                ImmutableList.of<QueryTaskFuture<Void?>?>(allTestsProcessedFuture, allTestSuitsVisitedFuture)
            )
        }

        fun partition(targets: Iterable<T?>): PartitionResult<T?> {
            val testTargetsBuilder = ImmutableList.builder<T?>()
            val testSuiteTargetsBuilder = ImmutableList.builder<T?>()
            val otherTargetsBuilder = ImmutableList.builder<T?>()

            for (target in targets) {
                if (accessor.isTestRule(target)) {
                    testTargetsBuilder.add(target)
                } else if (accessor.isTestSuite(target)) {
                    testSuiteTargetsBuilder.add(target)
                } else {
                    otherTargetsBuilder.add(target)
                }
            }

            return PartitionResult<T?>(
                testTargetsBuilder.build(), testSuiteTargetsBuilder.build(), otherTargetsBuilder.build()
            )
        }

        /**
         * Returns the set of rules named by the attribute 'attrName' of test_suite rule 'testSuite'.
         * The attribute must be a list of labels. If a target cannot be resolved, then an error is
         * reported to the environment (which may throw an exception if `keep_going` is disabled).
         * 
         * @precondition env.getAccessor().isTestSuite(testSuite)
         */
        @Throws(QueryException::class, InterruptedException::class)
        fun getPrerequisites(testSuite: T?, attrName: String?): Iterable<T?>? {
            return accessor.getPrerequisites(
                expression,
                testSuite,
                attrName,
                ("couldn't expand '"
                        + attrName
                        + "' attribute of test_suite "
                        + accessor.getLabel(testSuite)
                        + ": ")
            )
        }

        /**
         * Filters 'tests' (by mutation) according to the 'tags' attribute, specifically those that
         * match ALL of the tags in tagsAttribute.
         * 
         * @precondition `env.getAccessor().isTestSuite(testSuite)`
         * @precondition `env.getAccessor().isTestRule(test)`
         */
        fun includeTest(requiredTags: MutableSet<String?>, excludedTags: MutableSet<String?>, test: T?): Boolean {
            val testTags: MutableList<String?> = ArrayList<String?>(accessor.getStringListAttr(test, "tags"))
            testTags.add(accessor.getStringAttr(test, "size"))
            return includeTest(testTags, requiredTags, excludedTags)
        }
    }

    companion object {
        // TODO(ulfjack): This must match the code in TestTargetUtils. However, we don't currently want
        // to depend on the packages library. Extract to a neutral place?
        /**
         * Decides whether to include a test in a test_suite or not.
         * 
         * @param testTags Collection of all tags exhibited by a given test.
         * @param positiveTags Tags declared by the suite. A test must match ALL of these.
         * @param negativeTags Tags declared by the suite. A test must match NONE of these.
         * @return false is the test is to be removed.
         */
        private fun includeTest(
            testTags: MutableCollection<String?>,
            positiveTags: MutableCollection<String?>,
            negativeTags: MutableCollection<String?>
        ): Boolean {
            // Add this test if it matches ALL of the positive tags and NONE of the
            // negative tags in the tags attribute.
            for (tag in negativeTags) {
                if (testTags.contains(tag)) {
                    return false
                }
            }
            for (tag in positiveTags) {
                if (!testTags.contains(tag)) {
                    return false
                }
            }
            return true
        }

        /**
         * Separates a list of text "tags" into a Pair of Collections, where
         * the first element are the required or positive tags and the second element
         * are the excluded or negative tags.
         * This should work on tag list provided from the command line
         * --test_tags_filters flag or on tag filters explicitly declared in the
         * suite.
         * 
         * Keep this function in sync with the version in
         * java.com.google.devtools.build.lib.view.packages.TestTargetUtils.sortTagsBySense
         * 
         * @param tagList A collection of text tags to separate.
         */
        private fun sortTagsBySense(
            tagList: MutableCollection<String>, requiredTags: MutableSet<String?>, excludedTags: MutableSet<String?>
        ) {
            for (tag in tagList) {
                if (tag.startsWith("-")) {
                    excludedTags.add(tag.substring(1))
                } else if (tag.startsWith("+")) {
                    requiredTags.add(tag.substring(1))
                } else if (tag == "manual") {
                    // Ignore manual attribute because it is an exception: it is not a filter
                    // but a property of test_suite
                    continue
                } else {
                    requiredTags.add(tag)
                }
            }
        }
    }
}
