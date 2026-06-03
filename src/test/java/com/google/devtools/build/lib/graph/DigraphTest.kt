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
package com.google.devtools.build.lib.graph

import com.google.common.base.Function
import com.google.common.collect.ImmutableList
import com.google.common.collect.Lists
import com.google.devtools.build.lib.cmdline.Label
import org.junit.Test
import kotlin.Boolean
import kotlin.Comparator
import kotlin.Exception
import kotlin.Int
import kotlin.String
import kotlin.UnsupportedOperationException

/**
 * Test for [Digraph].
 */
@RunWith(JUnit4::class)
class DigraphTest {
    internal inner class FakeTarget(label: Label?) : Target {
        private val label: Label?

        init {
            this.label = label
        }

        public override fun getLabel(): Label? {
            return label
        }

        val packageoid: Packageoid?
            get() = null

        val packageMetadata: Package.Metadata?
            get() = null

        val packageDeclarations: Package.Declarations?
            get() = null

        val targetKind: String?
            get() = null

        val associatedRule: Rule?
            get() = null

        val license: License?
            get() = null

        val location: Location?
            get() = null

        val rawVisibility: RuleVisibility?
            get() = null

        val isConfigurable: Boolean
            get() = true

        public override fun reduceForSerialization(): TargetData? {
            throw UnsupportedOperationException()
        }
    }

    @Test
    @Throws(Exception::class)
    fun testStableOrdering() {
        val digraph = Digraph<Target?>()
        val a = FakeTarget(Label.create("pkg", "a"))
        val b = FakeTarget(Label.create("pkg", "b"))
        val c = FakeTarget(Label.create("pkg", "c"))
        val d = FakeTarget(Label.create("pkg", "d"))
        val e = FakeTarget(Label.create("pkg", "e"))
        val f = FakeTarget(Label.create("pkg", "f"))
        val g = FakeTarget(Label.create("pkg", "g"))
        //    f
        // / | | \
        // c g e d
        //      / \
        //      a  b
        digraph.addEdge(f, c)
        digraph.addEdge(f, g)
        digraph.addEdge(d, a)
        digraph.addEdge(d, b)
        digraph.addEdge(f, e)
        digraph.addEdge(f, d)

        // Get them back in topological and, within a valid topological ordering, alphabetical order.
        val comparator: Comparator<Target?> = object : Comparator<Target?> {
            override fun compare(o1: Target, o2: Target): Int {
                return o1.getLabel().compareTo(o2.getLabel()) * -1
            }
        }

        // Unwrap the Label from the Node<Target>, to make the final assert prettier.
        val unwrap: Function<in Node<Target?>?, Label?> =
            object : Function<Node<Target?>?, Label?> {
                override fun apply(node: Node<Target?>): Label {
                    return node.label.getLabel()
                }
            }
        val nodes: MutableList<Label?> =
            Lists.transform<Node<Target?>?, Label?>(digraph.getTopologicalOrder(comparator), unwrap)
        Truth.assertThat(nodes)
            .containsExactlyElementsIn(
                ImmutableList.of<E?>(
                    Label.create("pkg", "f"),
                    Label.create("pkg", "c"),
                    Label.create("pkg", "d"),
                    Label.create("pkg", "a"),
                    Label.create("pkg", "b"),
                    Label.create("pkg", "e"),
                    Label.create("pkg", "g")
                )
            )
    }
}
