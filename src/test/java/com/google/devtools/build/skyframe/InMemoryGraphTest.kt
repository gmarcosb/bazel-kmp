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
package com.google.devtools.build.skyframe

import com.google.devtools.build.lib.cmdline.PackageIdentifier

/** Tests for [InMemoryGraphImpl].  */
open class InMemoryGraphTest : GraphTest() {
    val startingVersion: Version
        get() = IntVersion.of(0)

    override fun getNextVersion(v: Version): Version {
        com.google.common.base.Preconditions.checkState(v is IntVersion)
        return (v as IntVersion).next()
    }

    override fun makeGraph() {
        graph = InMemoryGraphImpl()
    }

    override fun getGraph(version: Version?): ProcessableGraph? {
        return graph
    }

    /** Tests for [EdgelessInMemoryGraphImpl].  */
    class EdgelessInMemoryGraphTest : InMemoryGraphTest() {
        override fun makeGraph() {}

        override fun getGraph(version: Version?): ProcessableGraph {
            return EdgelessInMemoryGraphImpl( /* usePooledInterning= */true)
        }

        override fun getStartingVersion(): Version {
            return Version.constant()
        }

        override fun getNextVersion(version: Version?): Version? {
            throw java.lang.UnsupportedOperationException()
        }

        override fun shouldTestIncrementality(): Boolean {
            return false
        }
    }

    class SkyKeyWithSkyKeyInterner private constructor(arg: String?) : AbstractSkyKey<String?>(arg) {
        public override fun functionName(): SkyFunctionName {
            return SkyFunctionName.FOR_TESTING
        }

        val skyKeyInterner: SkyKeyInterner<SkyKeyWithSkyKeyInterner?>
            get() = interner

        companion object {
            private val interner: SkyKeyInterner<SkyKeyWithSkyKeyInterner?> = SkyKey.newInterner()

            fun create(arg: String?): SkyKeyWithSkyKeyInterner {
                return interner.intern(SkyKeyWithSkyKeyInterner(arg))
            }
        }
    }

    @org.junit.Test
    @Throws(java.lang.InterruptedException::class)
    fun createIfAbsentBatch_skyKeyWithSkyKeyInterner() {
        val cat: SkyKey = SkyKeyWithSkyKeyInterner.Companion.create("cat")

        // Insert cat SkyKey into graph.
        // (1) result of getting cat node from graph should not be null;
        // (2) when re-creating cat SkyKeyWithSkyKeyInterner object, it should retrieve the instance
        // from global pool (graph), which is also the same instance as the original one.
        graph.createIfAbsentBatch(null, Reason.OTHER, com.google.common.collect.ImmutableList.of<E?>(cat))
        assertThat(graph.get(null, Reason.OTHER, cat)).isNotNull()
        Truth.assertThat(SkyKeyWithSkyKeyInterner.Companion.create("cat")).isSameInstanceAs(cat)

        // Remove cat SkyKey from graph.
        // (1) result of getting cat node from graph should be null, indicating the cat key has been
        // removed from the global pool (graph);
        // (2) since when removing key from global pool (graph), the removed key will be re-interned
        // back to weak interner. So re-creating an equal "cat" object from SkyKeyWithSkyKeyInterner
        // will result in the same instance to be returned (no new instance will be created).
        graph.remove(cat)
        assertThat(graph.get(null, Reason.OTHER, cat)).isNull()
        Truth.assertThat(SkyKeyWithSkyKeyInterner.Companion.create("cat")).isSameInstanceAs(cat)
    }

    @org.junit.Test
    @Throws(java.lang.InterruptedException::class)
    fun cleanupPool_weakInternerReintern() {
        val cat: SkyKey = SkyKeyWithSkyKeyInterner.Companion.create("cat")

        graph.createIfAbsentBatch(null, Reason.OTHER, com.google.common.collect.ImmutableList.of<E?>(cat))
        assertThat(graph.get(null, Reason.OTHER, cat)).isNotNull()

        assertThat(graph).isInstanceOf(InMemoryGraphImpl::class.java)
        (graph as InMemoryGraphImpl).cleanupInterningPools()

        // When re-creating a cat SkyKeyWithSkyKeyInterner, we expect to get the original instance. Pool
        // cleaning up re-interns the cat instance back to the weak interner, and thus, no new instance
        // is created.
        Truth.assertThat(SkyKeyWithSkyKeyInterner.Companion.create("cat")).isSameInstanceAs(cat)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun removePackageNode_notPresentInGraph() {
        val packageIdentifier: PackageIdentifier? = PackageIdentifier.createUnchecked("repo", "hello")

        graph.remove(packageIdentifier)
        assertThat(graph.get(null, Reason.OTHER, packageIdentifier)).isNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun removePackageNode_noValueWeakInternLabelsNoCrash() {
        val packageIdentifier: PackageIdentifier = PackageIdentifier.createUnchecked("repo", "hello")

        graph.createIfAbsentBatch(null, Reason.OTHER, com.google.common.collect.ImmutableList.of<E?>(packageIdentifier))
        val entry: NodeEntry = graph.get(null, Reason.OTHER, packageIdentifier)
        assertThat(entry.toValue()).isNull()

        graph.remove(packageIdentifier)
        assertThat(graph.get(null, Reason.OTHER, packageIdentifier)).isNull()
    }
}
