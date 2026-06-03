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
package com.google.devtools.build.lib.collect.nestedset

import com.google.devtools.build.lib.collect.nestedset.ExpanderTestBase
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * Tests for [LinkOrderExpander].
 */
@RunWith(JUnit4::class)
class LinkOrderExpanderTest : ExpanderTestBase() {
    override fun expanderOrder(): Order {
        return Order.LINK_ORDER
    }

    override fun nestedResult(): MutableList<String?> {
        return com.google.common.collect.ImmutableList.of<String?>("b", "d", "c", "a", "e")
    }

    override fun nestedDuplicatesResult(): MutableList<String?> {
        return com.google.common.collect.ImmutableList.of<String?>("b", "d", "c", "a", "e")
    }

    override fun chainResult(): MutableList<String?> {
        return com.google.common.collect.ImmutableList.of<String?>("a", "b", "c")
    }

    override fun diamondResult(): MutableList<String?> {
        return com.google.common.collect.ImmutableList.of<String?>("a", "b", "c", "d")
    }

    override fun orderConflictResult(): MutableList<String?> {
        // Rightmost branch determines the order.
        return com.google.common.collect.ImmutableList.of<String?>("b", "a")
    }

    override fun extendedDiamondResult(): MutableList<String?> {
        return com.google.common.collect.ImmutableList.of<String?>("a", "b", "c", "e", "d")
    }

    override fun extendedDiamondRightArmResult(): MutableList<String?> {
        return com.google.common.collect.ImmutableList.of<String?>("a", "b", "c", "c2", "e", "d")
    }
}
