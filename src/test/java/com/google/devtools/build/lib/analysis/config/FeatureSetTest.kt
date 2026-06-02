// Copyright 2023 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.analysis.config

import com.google.devtools.build.lib.analysis.config.FeatureSet.merge

@RunWith(JUnit4::class)
class FeatureSetTest {
    @org.junit.Test
    fun parse_basic() {
        val featureSet: FeatureSet = parse("foo", "bar", "-baz")
        assertThat(featureSet.on()).containsExactly("foo", "bar")
        assertThat(featureSet.off()).containsExactly("baz")
    }

    @org.junit.Test
    fun parse_offTrumpsOn() {
        val featureSet: FeatureSet = parse("foo", "bar", "-bar")
        assertThat(featureSet.on()).containsExactly("foo")
        assertThat(featureSet.off()).containsExactly("bar")
    }

    @org.junit.Test
    fun parse_noLayeringCheck() {
        val featureSet: FeatureSet = parse("foo", "bar", "no_layering_check")
        assertThat(featureSet.on()).containsExactly("foo", "bar")
        assertThat(featureSet.off()).containsExactly("layering_check")
    }

    @org.junit.Test
    fun merge_basic() {
        assertThat(merge(parse("foo", "-bar"), parse("kek", "-lol")))
            .isEqualTo(parse("foo", "kek", "-bar", "-lol"))
    }

    @org.junit.Test
    fun merge_fineTrumpsCoarse() {
        assertThat(merge(parse("foo", "-bar"), parse("bar", "-foo"))).isEqualTo(parse("-foo", "bar"))
    }

    @org.junit.Test
    fun merge_associativity() {
        val a: FeatureSet = parse("foo", "bar", "-baz")
        val b: FeatureSet = parse("-foo", "-bar")
        val c: FeatureSet = parse("foo", "baz")
        assertThat(merge(a, merge(b, c))).isEqualTo(merge(merge(a, b), c))
        assertThat(merge(b, merge(c, a))).isEqualTo(merge(merge(b, c), a))
        assertThat(merge(c, merge(b, a))).isEqualTo(merge(merge(c, b), a))
    }

    @org.junit.Test
    fun mergeWithGlobalFeatures_basic() {
        assertThat(FeatureSet.mergeWithGlobalFeatures(parse("foo", "-bar"), parse("kek", "-lol")))
            .isEqualTo(parse("foo", "kek", "-bar", "-lol"))
    }

    @org.junit.Test
    fun mergeWithGlobalFeatures_globalOffTrumpsEverything() {
        assertThat(FeatureSet.mergeWithGlobalFeatures(parse("foo", "-bar"), parse("bar", "-foo")))
            .isEqualTo(parse("-bar", "-foo"))
    }

    companion object {
        private fun parse(vararg features: String?): FeatureSet {
            return FeatureSet.parse(java.util.Arrays.< T > asList < T ? > (features))
        }
    }
}
