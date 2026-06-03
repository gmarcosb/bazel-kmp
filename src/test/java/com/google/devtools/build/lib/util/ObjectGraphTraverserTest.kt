// Copyright 2024 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.util

import com.google.devtools.build.lib.util.ObjectGraphTraverser.DomainSpecificTraverser

@RunWith(JUnit4::class)
class ObjectGraphTraverserTest {
    private class Edge(private val from: Any?, private val to: Any?, type: EdgeType?) {
        private val type: EdgeType?

        init {
            this.type = type
        }

        override fun equals(o: Any?): Boolean {
            if (o !is Edge) {
                return false
            }

            return o.from === from && o.to === to && o.type === type
        }

        override fun hashCode(): Int {
            return java.util.Objects.hash(
                java.lang.System.identityHashCode(from),
                java.lang.System.identityHashCode(to),
                type
            )
        }

        companion object {
            private fun of(from: Any?, to: Any?, type: EdgeType?): Edge {
                return com.google.devtools.build.lib.util.ObjectGraphTraverserTest.Edge(from, to, type)
            }
        }
    }

    private class LoggingObjectReceiver : ObjectGraphTraverser.ObjectReceiver {
        private val objects: MutableList<Any?> = java.util.ArrayList<Any?>()
        private val objectContexts: MutableMap<Any?, String?> = HashMap<Any?, String?>()
        private val edges: MutableList<Edge?> = java.util.ArrayList<Edge?>()
        private val edgeContexts: MutableMap<Edge?, String?> = HashMap<Edge?, String?>()

        public override fun objectFound(o: Any?, context: String?) {
            objects.add(o)
            if (context != null) {
                objectContexts.put(o, context)
            }
        }

        public override fun edgeFound(from: Any?, to: Any?, toContext: String?, edgeType: EdgeType?) {
            val edge: Edge =
                com.google.devtools.build.lib.util.ObjectGraphTraverserTest.Edge.Companion.of(from, to, edgeType)

            edges.add(edge)
            if (toContext != null) {
                edgeContexts.put(edge, toContext)
            }
        }
    }

    private fun createObjectGraphTraverser(
        domainSpecific: DomainSpecificTraverser?,
        seen: ConcurrentIdentitySet?,
        receiver: LoggingObjectReceiver?,
        collectContext: Boolean
    ): ObjectGraphTraverser {
        val traversers: com.google.common.collect.ImmutableList<DomainSpecificTraverser?> =
            if (domainSpecific == null) com.google.common.collect.ImmutableList.of<DomainSpecificTraverser?>() else com.google.common.collect.ImmutableList.of<DomainSpecificTraverser?>(
                domainSpecific
            )
        return ObjectGraphTraverser(
            FieldCache(traversers), false, true, seen, collectContext, receiver, null
        )
    }

    @org.junit.Test
    fun smoke() {
        val o1 = Any()
        val o2 = Any()
        val array: Any = arrayOf<Any>(o2)
        val pair: Any? = Pair.of(o1, array)

        val seen: ConcurrentIdentitySet = ConcurrentIdentitySet(1)
        val receiver = LoggingObjectReceiver()
        val cut: ObjectGraphTraverser = createObjectGraphTraverser(null, seen, receiver, false)
        cut.traverse(pair)

        Truth.assertThat(receiver.objects).containsExactly(o1, o2, array, pair)
        Truth.assertThat(receiver.edges).hasSize(3)
    }

    @org.junit.Test
    fun testAdmit() {
        val o1 = Any()
        val o2 = Any()
        val pair1: Any? = Pair.of(o1, o1)
        val pair2: Any? = Pair.of(o2, o2)
        val pair3: Any? = Pair.of(pair1, pair2)

        val domainSpecific: DomainSpecificTraverser =
            Mockito.mock<DomainSpecificTraverser>(DomainSpecificTraverser::class.java)
        Mockito.`when`<T?>(domainSpecific.admit(ArgumentMatchers.any<T?>()))
            .thenAnswer(Answer { i: InvocationOnMock? -> i.getArgument<Any?>(0) !== pair2 })

        val seen: ConcurrentIdentitySet = ConcurrentIdentitySet(1)
        val receiver = LoggingObjectReceiver()
        val cut: ObjectGraphTraverser = createObjectGraphTraverser(domainSpecific, seen, receiver, false)
        cut.traverse(pair3)

        Truth.assertThat(receiver.objects).containsExactly(o1, pair1, pair3)
        Truth.assertThat(receiver.edges).hasSize(3)
    }

    @org.junit.Test
    fun testCustomTraversal() {
        val o1 = Any()
        val o2 = Any()

        val domainSpecific: DomainSpecificTraverser =
            Mockito.mock<DomainSpecificTraverser>(DomainSpecificTraverser::class.java)
        Mockito.`when`<T?>(domainSpecific.admit(ArgumentMatchers.any<T?>())).thenReturn(true)
        Mockito.`when`<T?>(domainSpecific.maybeTraverse(ArgumentMatchers.any<T?>(), ArgumentMatchers.any<T?>()))
            .thenAnswer(
                Answer { i: InvocationOnMock? ->
                    val arg: Any? = i.getArgument<Any?>(0)
                    val traversal: Traversal = i.getArgument<Traversal>(1)

                    if (arg !== o1) {
                        return@thenAnswer false
                    }

                    traversal.objectFound(o1, null)
                    traversal.edgeFound(o2, null)
                    true
                })

        val seen: ConcurrentIdentitySet = ConcurrentIdentitySet(1)
        val receiver = LoggingObjectReceiver()
        val cut: ObjectGraphTraverser = createObjectGraphTraverser(domainSpecific, seen, receiver, false)
        cut.traverse(o1)

        Truth.assertThat(receiver.objects).containsExactly(o1, o2)
        Truth.assertThat(receiver.edges).containsExactly(
            com.google.devtools.build.lib.util.ObjectGraphTraverserTest.Edge.Companion.of(
                o1,
                o2,
                EdgeType.CURRENT_TRAVERSAL
            )
        )
    }

    @org.junit.Test
    fun testIgnoredFields() {
        val o1 = Any()
        val o2 = Any()
        val pair: Any? = Pair.of(o1, o2)

        val domainSpecific: DomainSpecificTraverser =
            Mockito.mock<DomainSpecificTraverser>(DomainSpecificTraverser::class.java)
        Mockito.`when`<T?>(domainSpecific.ignoredFields(Pair::class.java))
            .thenReturn(com.google.common.collect.ImmutableSet.of<E?>("second"))
        Mockito.`when`<T?>(domainSpecific.admit(ArgumentMatchers.any<T?>())).thenReturn(true)

        val seen: ConcurrentIdentitySet = ConcurrentIdentitySet(1)
        val receiver = LoggingObjectReceiver()
        val cut: ObjectGraphTraverser = createObjectGraphTraverser(domainSpecific, seen, receiver, false)
        cut.traverse(pair)

        Truth.assertThat(receiver.objects).containsExactly(o1, pair)
        Truth.assertThat(receiver.edges).containsExactly(
            com.google.devtools.build.lib.util.ObjectGraphTraverserTest.Edge.Companion.of(
                pair,
                o1,
                EdgeType.CURRENT_TRAVERSAL
            )
        )
    }

    @org.junit.Test
    fun testSeenObjects() {
        val o1 = Any()
        val o2 = Any()
        val pair: Any? = Pair.of(o1, o2)

        val seen: ConcurrentIdentitySet = ConcurrentIdentitySet(1)
        val unused: Boolean = seen.add(o2)
        val receiver = LoggingObjectReceiver()
        val cut: ObjectGraphTraverser = createObjectGraphTraverser(null, seen, receiver, false)
        cut.traverse(pair)

        Truth.assertThat(receiver.objects).containsExactly(o1, pair)
        Truth.assertThat(receiver.edges)
            .containsExactly(
                com.google.devtools.build.lib.util.ObjectGraphTraverserTest.Edge.Companion.of(
                    pair,
                    o1,
                    EdgeType.CURRENT_TRAVERSAL
                ),
                com.google.devtools.build.lib.util.ObjectGraphTraverserTest.Edge.Companion.of(
                    pair,
                    o2,
                    EdgeType.ALREADY_SEEN
                )
            )
    }

    private class Outer {
        fun createInner(): Inner {
            return Inner()
        }

        private inner class Inner {
            @get:Suppress("unused")
            val outer: Outer
                // Java is clever and will optimize out the reference to Outer without this
                get() = this@Outer
        }
    }

    @org.junit.Test
    fun testNonStaticClassTraversesEnclosingClass() {
        val outer = Outer()
        val inner: Inner = outer.createInner()

        val seen: ConcurrentIdentitySet = ConcurrentIdentitySet(1)
        val receiver = LoggingObjectReceiver()
        val cut: ObjectGraphTraverser = createObjectGraphTraverser(null, seen, receiver, false)

        cut.traverse(inner)
        Truth.assertThat(receiver.objects).containsExactly(outer, inner)
    }

    @org.junit.Test
    fun testLambdaClosingOverNothingReported() {
        val o1 = Any()
        val lambda: java.util.function.Supplier<Any?> = java.util.function.Supplier { 3 }
        val pair: Any? = Pair.of(o1, lambda)

        val seen: ConcurrentIdentitySet = ConcurrentIdentitySet(1)
        val receiver = LoggingObjectReceiver()
        val cut: ObjectGraphTraverser = createObjectGraphTraverser(null, seen, receiver, false)

        cut.traverse(pair)
        Truth.assertThat(receiver.objects).containsExactly(pair, o1, lambda)
    }

    @org.junit.Test
    fun testLambdaClosingOverNothingReportedWhenReferencedTwice() {
        val lambda: java.util.function.Supplier<Any?> = java.util.function.Supplier { 3 }
        val pair: Any? = Pair.of(lambda, lambda)

        val seen: ConcurrentIdentitySet = ConcurrentIdentitySet(1)
        val receiver = LoggingObjectReceiver()
        val cut: ObjectGraphTraverser = createObjectGraphTraverser(null, seen, receiver, false)

        cut.traverse(pair)
        Truth.assertThat(receiver.objects).containsExactly(pair, lambda)
    }

    @org.junit.Test
    fun testValuesClosedOverReported() {
        val o1 = Any()
        val lambda: java.util.function.Supplier<Any?> = java.util.function.Supplier { o1 }

        val seen: ConcurrentIdentitySet = ConcurrentIdentitySet(1)
        val receiver = LoggingObjectReceiver()
        val cut: ObjectGraphTraverser = createObjectGraphTraverser(null, seen, receiver, false)

        cut.traverse(lambda)
        Truth.assertThat(receiver.objects).containsExactly(lambda, o1)
    }

    @org.junit.Test
    fun testMultipleClosuresWithSameCodeReported() {
        val o1 = Any()
        val o2 = Any()
        val generator: java.util.function.Function<Any?, java.util.function.Supplier<Any?>?> =
            java.util.function.Function { o: Any? -> java.util.function.Supplier { o } }
        val l1: Any? = generator.apply(o1)
        val l2: Any? = generator.apply(o2)
        val pair: Any? = Pair.of(l1, l2)

        val seen: ConcurrentIdentitySet = ConcurrentIdentitySet(1)
        val receiver = LoggingObjectReceiver()
        val cut: ObjectGraphTraverser = createObjectGraphTraverser(null, seen, receiver, false)

        cut.traverse(pair)
        Truth.assertThat(receiver.objects).containsExactly(pair, l1, l2, o1, o2)
    }

    @org.junit.Test
    fun testEdgeContexts() {
        val o1 = Any()
        val o2 = Any()
        val array: Any = arrayOf<Any>(o2)
        val pair: Any? = Pair.of(o1, array)

        val seen: ConcurrentIdentitySet = ConcurrentIdentitySet(1)
        val receiver = LoggingObjectReceiver()
        val domainSpecific: DomainSpecificTraverser =
            Mockito.mock<DomainSpecificTraverser>(DomainSpecificTraverser::class.java)
        Mockito.`when`<T?>(domainSpecific.admit(ArgumentMatchers.any<T?>())).thenReturn(true)
        Mockito.`when`<T?>(
            domainSpecific.contextForField(
                ArgumentMatchers.refEq<T?>(pair),
                ArgumentMatchers.any<T?>(),
                ArgumentMatchers.any<T?>(),
                ArgumentMatchers.refEq<T?>(o1)
            )
        )
            .thenReturn("o1context")
        Mockito.`when`<T?>(
            domainSpecific.contextForArrayItem(
                ArgumentMatchers.refEq<T?>(array),
                ArgumentMatchers.any<T?>(),
                ArgumentMatchers.refEq<T?>(o2)
            )
        )
            .thenReturn("o2context")
        val cut: ObjectGraphTraverser = createObjectGraphTraverser(domainSpecific, seen, receiver, true)

        cut.traverse(pair)
        Truth.assertThat(receiver.edgeContexts)
            .containsEntry(
                com.google.devtools.build.lib.util.ObjectGraphTraverserTest.Edge.Companion.of(
                    pair,
                    o1,
                    EdgeType.CURRENT_TRAVERSAL
                ), "o1context"
            )
        Truth.assertThat(receiver.edgeContexts)
            .containsEntry(
                com.google.devtools.build.lib.util.ObjectGraphTraverserTest.Edge.Companion.of(
                    array,
                    o2,
                    EdgeType.CURRENT_TRAVERSAL
                ), "o2context"
            )
        Truth.assertThat(receiver.objectContexts).containsEntry(o1, "o1context")
        Truth.assertThat(receiver.objectContexts).containsEntry(o2, "o2context")
    }

    @org.junit.Test
    fun testObjectContexts() {
        val o1 = Any()
        val o2 = Any()
        val pair: Any? = Pair.of(o1, o2)

        val seen: ConcurrentIdentitySet = ConcurrentIdentitySet(1)
        val receiver = LoggingObjectReceiver()
        val domainSpecific: DomainSpecificTraverser =
            Mockito.mock<DomainSpecificTraverser>(DomainSpecificTraverser::class.java)
        Mockito.`when`<T?>(domainSpecific.admit(ArgumentMatchers.any<T?>())).thenReturn(true)
        Mockito.`when`<T?>(
            domainSpecific.contextForField(
                ArgumentMatchers.refEq<T?>(pair),
                ArgumentMatchers.any<T?>(),
                ArgumentMatchers.any<T?>(),
                ArgumentMatchers.refEq<T?>(o1)
            )
        ).thenReturn("bad")
        Mockito.`when`<T?>(domainSpecific.maybeTraverse(ArgumentMatchers.any<T?>(), ArgumentMatchers.any<T?>()))
            .thenAnswer(
                Answer { i: InvocationOnMock? ->
                    val o: Any? = i.getArgument<Any?>(0)
                    val traversal: Traversal = i.getArgument<Traversal>(1)
                    if (o === o1) {
                        traversal.objectFound(o, "o1context")
                        return@thenAnswer true
                    } else if (o === o2) {
                        traversal.objectFound(o, "o2context")
                        return@thenAnswer true
                    } else {
                        return@thenAnswer false
                    }
                })
        val cut: ObjectGraphTraverser = createObjectGraphTraverser(domainSpecific, seen, receiver, true)

        cut.traverse(pair)
        Truth.assertThat(receiver.objectContexts).containsEntry(o1, "o1context") // overrides edge context
        Truth.assertThat(receiver.objectContexts).containsEntry(o2, "o2context")
    }
}
