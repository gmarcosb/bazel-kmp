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
package com.google.devtools.build.lib.packages

import com.google.devtools.build.lib.cmdline.Label

/**
 * Checks against a class initialization deadlock. "query sometimes hangs".
 * 
 * 
 * This requires static initialization of PackageGroup and PackageSpecification to occur in a
 * multithreaded context, and therefore must be in its own class.
 */
@RunWith(JUnit4::class)
class PackageGroupStaticInitializationTest : PackageLoadingTestCase() {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNoDeadlockOnPackageGroupCreation() {
        scratch.file("fruits/BUILD", "package_group(name = 'mango', packages = ['//...'])")

        val groupQueue: SynchronousQueue<PackageSpecification?> = SynchronousQueue<PackageSpecification?>()
        val producingThread: TestThread =
            TestThread(
                TestRunnable {
                    try {
                        val defaultRepoName: RepositoryName? =
                            Label.parseCanonicalUnchecked("//context").getRepository()
                        groupQueue.put(
                            PackageSpecification.fromString(
                                RepositoryMapping.EMPTY,
                                defaultRepoName,
                                "//fruits/...",  /* allowPublicPrivate= */
                                true,  /* repoRootMeansCurrentRepo= */
                                true
                            )
                        )
                    } catch (e: java.lang.Exception) {
                        // Can't throw from Runnable, but this will cause the test to timeout
                        // when the consumer can't take the object.
                        e.printStackTrace()
                    }
                })

        val consumingThread: TestThread =
            TestThread(
                TestRunnable {
                    try {
                        getTarget("//fruits:mango")
                        groupQueue.take()
                    } catch (e: java.lang.Exception) {
                        // Can't throw from Runnable, but this will cause the test to timeout
                        // when the producer can't put the object.
                        e.printStackTrace()
                    }
                })

        consumingThread.start()
        producingThread.start()

        producingThread.joinAndAssertState(com.google.devtools.build.lib.testutil.TestUtils.WAIT_TIMEOUT_MILLISECONDS)
        consumingThread.joinAndAssertState(com.google.devtools.build.lib.testutil.TestUtils.WAIT_TIMEOUT_MILLISECONDS)
    }
}
