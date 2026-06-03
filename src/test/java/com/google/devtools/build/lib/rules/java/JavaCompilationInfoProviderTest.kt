// Copyright 2017 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.rules.java

import com.google.devtools.build.lib.actions.Artifact

/** Unit tests for [JavaInfo].  */
@RunWith(JUnit4::class)
class JavaCompilationInfoProviderTest {
    /**
     * Tests the [JavaCompilationInfoProvider] `equals` and `hashcode`
     * implementations.
     * 
     * 
     * The key thing we are testing is that the [JavaCompilationInfoProvider.bootClasspath]
     * matches for different [NestedSet] instances as long as they have the same content. Other
     * fields, such as [JavaCompilationInfoProvider.compilationClasspath] are required to be the
     * exact same [NestedSet] instance.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun compilationInfo_equalityTests() {
        val jar: Artifact = createArtifact("foo.jar")
        val fixedNestedSet: NestedSet<Artifact?>? = NestedSetBuilder.create(Order.STABLE_ORDER, jar)
        val empty1: JavaCompilationInfoProvider? =
            com.google.devtools.build.lib.rules.java.JavaCompilationInfoProvider.Builder().build()
        val empty2: JavaCompilationInfoProvider? =
            com.google.devtools.build.lib.rules.java.JavaCompilationInfoProvider.Builder().build()
        val withBootCpNewNestedSet1: JavaCompilationInfoProvider? =
            com.google.devtools.build.lib.rules.java.JavaCompilationInfoProvider.Builder()
                .setBootClasspath(NestedSetBuilder.create(Order.STABLE_ORDER, jar))
                .build()
        val withBootCpNewNestedSet2: JavaCompilationInfoProvider? =
            com.google.devtools.build.lib.rules.java.JavaCompilationInfoProvider.Builder()
                .setBootClasspath(NestedSetBuilder.create(Order.STABLE_ORDER, jar))
                .build()
        val withBootCpNewEmptyNestedSet1: JavaCompilationInfoProvider? =
            com.google.devtools.build.lib.rules.java.JavaCompilationInfoProvider.Builder()
                .setBootClasspath(NestedSetBuilder.emptySet(Order.STABLE_ORDER))
                .build()
        val withBootCpNewEmptyNestedSet2: JavaCompilationInfoProvider? =
            com.google.devtools.build.lib.rules.java.JavaCompilationInfoProvider.Builder()
                .setBootClasspath(NestedSetBuilder.emptySet(Order.STABLE_ORDER))
                .build()
        val withCompileCpNewNestedSet1: JavaCompilationInfoProvider? =
            com.google.devtools.build.lib.rules.java.JavaCompilationInfoProvider.Builder()
                .setCompilationClasspath(NestedSetBuilder.create(Order.STABLE_ORDER, jar))
                .build()
        val withCompileCpNewNestedSet2: JavaCompilationInfoProvider? =
            com.google.devtools.build.lib.rules.java.JavaCompilationInfoProvider.Builder()
                .setCompilationClasspath(NestedSetBuilder.create(Order.STABLE_ORDER, jar))
                .build()
        val withCompileCpFixedNestedSet1: JavaCompilationInfoProvider? =
            com.google.devtools.build.lib.rules.java.JavaCompilationInfoProvider.Builder()
                .setCompilationClasspath(fixedNestedSet).build()
        val withCompileCpFixedNestedSet2: JavaCompilationInfoProvider? =
            com.google.devtools.build.lib.rules.java.JavaCompilationInfoProvider.Builder()
                .setCompilationClasspath(fixedNestedSet).build()

        EqualsTester()
            .addEqualityGroup(
                empty1, empty2, withBootCpNewEmptyNestedSet1, withBootCpNewEmptyNestedSet2
            )
            .addEqualityGroup(withBootCpNewNestedSet1, withBootCpNewNestedSet2)
            .addEqualityGroup(withCompileCpNewNestedSet1)
            .addEqualityGroup(withCompileCpNewNestedSet2)
            .addEqualityGroup(withCompileCpFixedNestedSet1, withCompileCpFixedNestedSet2)
            .testEquals()
    }

    companion object {
        @Throws(IOException::class)
        private fun createArtifact(path: String?): Artifact {
            val execRoot: Path = Scratch().dir("/")
            val root: ArtifactRoot? = ArtifactRoot.asDerivedRoot(execRoot, RootType.OUTPUT, "fake-root")
            return ActionsTestUtil.createArtifact(root, path)
        }
    }
}
