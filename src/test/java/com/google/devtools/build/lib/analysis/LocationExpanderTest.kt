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
package com.google.devtools.build.lib.analysis

import com.google.devtools.build.lib.analysis.LocationExpander.LocationFunction

/** Unit tests for [LocationExpander].  */
@RunWith(JUnit4::class)
class LocationExpanderTest {
    private class Capture : RuleErrorConsumer {
        private val warnsOrErrors: MutableList<String?> = java.util.ArrayList<String?>()

        public override fun ruleWarning(message: String?) {
            warnsOrErrors.add("WARN: " + message)
        }

        public override fun ruleError(message: String?) {
            warnsOrErrors.add("ERROR: " + message)
        }

        public override fun attributeWarning(attrName: String?, message: String?) {
            warnsOrErrors.add("WARN-" + attrName + ": " + message)
        }

        public override fun attributeError(attrName: String?, message: String?) {
            warnsOrErrors.add("ERROR-" + attrName + ": " + message)
        }

        public override fun hasErrors(): Boolean {
            return !warnsOrErrors.isEmpty()
        }
    }

    @Throws(java.lang.Exception::class)
    private fun makeExpander(ruleErrorConsumer: RuleErrorConsumer?): LocationExpander {
        val f1: LocationFunction =
            LocationFunctionBuilder("//a", false)
                .setPathType(LocationFunction.PathType.LOCATION)
                .add("//a", "/exec/src/a")
                .build()

        val f2: LocationFunction =
            LocationFunctionBuilder("//b", true)
                .setPathType(LocationFunction.PathType.LOCATION)
                .add("//b", "/exec/src/b")
                .build()

        return LocationExpander(
            ruleErrorConsumer,
            com.google.common.collect.ImmutableMap.of<String?, LocationFunction?>(
                "location", f1,
                "locations", f2
            ),
            RepositoryMapping.EMPTY,
            "workspace"
        )
    }

    @Throws(java.lang.Exception::class)
    private fun expand(input: String?): String {
        return makeExpander(Capture()).expand(input)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun noExpansion() {
        Truth.assertThat(expand("abc")).isEqualTo("abc")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun oneOrMore() {
        Truth.assertThat(expand("$(location a)")).isEqualTo("src/a")
        Truth.assertThat(expand("$(locations b)")).isEqualTo("src/b")
        Truth.assertThat(expand("---$(location a)---")).isEqualTo("---src/a---")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun twoInOne() {
        Truth.assertThat(expand("$(location a) $(locations b)")).isEqualTo("src/a src/b")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun notAFunction() {
        Truth.assertThat(expand("$(locationz a)")).isEqualTo("$(locationz a)")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun missingClosingParen() {
        val capture = Capture()
        val value: String? = makeExpander(capture).expand("foo $(location a")
        // In case of an error, no location expansion is performed.
        Truth.assertThat(value).isEqualTo("foo $(location a")
        Truth.assertThat(capture.warnsOrErrors).containsExactly("ERROR: unterminated $(location) expression")
    }

    // In case of errors, the exact return value is unspecified. However, we don't want to
    // accidentally change the behavior even in this unspecified case - that's why I added a test
    // here.
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun noExpansionOnError() {
        val capture = Capture()
        val value: String? = makeExpander(capture).expand("foo $(location a) $(location a")
        Truth.assertThat(value).isEqualTo("foo $(location a) $(location a")
        Truth.assertThat(capture.warnsOrErrors).containsExactly("ERROR: unterminated $(location) expression")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun expansionWithRepositoryMapping() {
        val f1: LocationFunction =
            LocationFunctionBuilder("//a", false)
                .setPathType(LocationFunction.PathType.LOCATION)
                .add("@bar//a", "/exec/src/a")
                .build()

        val repositoryMapping: com.google.common.collect.ImmutableMap<String?, RepositoryName?> =
            com.google.common.collect.ImmutableMap.of<K?, V?>("foo", RepositoryName.create("bar"))

        val locationExpander: LocationExpander =
            LocationExpander(
                Capture(),
                com.google.common.collect.ImmutableMap.of<String?, LocationFunction?>("location", f1),
                RepositoryMapping.create(repositoryMapping, RepositoryName.MAIN),
                "workspace"
            )

        val value: String? = locationExpander.expand("$(location @foo//a)")
        Truth.assertThat(value).isEqualTo("src/a")
    }
}
