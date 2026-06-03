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
package com.google.devtools.build.lib.skyframe.serialization

import com.google.protobuf.CodedInputStream
import com.google.protobuf.CodedOutputStream
import java.io.IOException
import java.util.Collections
import java.util.HashSet
import java.util.Random
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch

/** A nested-set like class for codec testing.  */
internal class NotNestedSet {
    var contents: Array<Any?>? // mutable is more convenient in test code
        private set

    constructor(contents: Array<Any?>?) {
        this.contents = contents
    }

    private constructor()

    private class NodeBuilder {
        private val children: HashSet<Coordinate> = HashSet<Coordinate>()
        private var value: Array<Any?>?

        fun addChild(layer: Int, index: Int) {
            children.add(Coordinate.Companion.create(layer, index))
        }
    }

    @kotlin.jvm.JvmRecord
    internal data class Coordinate(val layer: Int, val index: Int) {
        companion object {
            private fun create(layer: Int, index: Int): Coordinate {
                return Coordinate(layer, index)
            }
        }
    }

    internal class NotNestedSetCodec(private val innerCodec: NestedArrayCodec?) : AsyncObjectCodec<NotNestedSet?>() {
        val encodedClass: java.lang.Class<NotNestedSet?>
            get() = NotNestedSet::class.java

        @Throws(SerializationException::class, IOException::class)
        public override fun serialize(
            context: SerializationContext, set: NotNestedSet, codedOut: CodedOutputStream?
        ) {
            context.putSharedValue(set.contents,  /* distinguisher= */null, innerCodec, codedOut)
        }

        @Throws(SerializationException::class, IOException::class)
        public override fun deserializeAsync(
            context: AsyncDeserializationContext, codedIn: CodedInputStream?
        ): NotNestedSet {
            val value = NotNestedSet()
            context.registerInitialValue(value)
            context.getSharedValue(
                codedIn,  /* distinguisher= */
                null,
                innerCodec,
                value,
                { set: NotNestedSet, contents: Any? -> setContents(set, contents) })
            return value
        }
    }

    internal class NotNestedSetDeferredCodec(private val innerCodec: NestedArrayCodec?) :
        DeferredObjectCodec<NotNestedSet?>() {
        val encodedClass: java.lang.Class<NotNestedSet?>
            get() = NotNestedSet::class.java

        @Throws(SerializationException::class, IOException::class)
        public override fun serialize(
            context: SerializationContext, set: NotNestedSet, codedOut: CodedOutputStream?
        ) {
            context.putSharedValue(set.contents,  /* distinguisher= */null, innerCodec, codedOut)
        }

        @Throws(SerializationException::class, IOException::class)
        public override fun deserializeDeferred(
            context: AsyncDeserializationContext, codedIn: CodedInputStream?
        ): DeferredValue<NotNestedSet?> {
            val value = NotNestedSet()
            context.getSharedValue(
                codedIn,  /* distinguisher= */
                null,
                innerCodec,
                value,
                { set: NotNestedSet, contents: Any? -> setContents(set, contents) })
            return DeferredValue { value }
        }
    }

    internal class NestedArrayCodec : DeferredObjectCodec<Array<Any?>?>() {
        // deliberate use of reference equality
        private val serializeDelays: ConcurrentHashMap<Array<Any?>?, InjectedDelay?> =
            ConcurrentHashMap<Array<Any?>?, InjectedDelay?>()

        public override fun autoRegister(): Boolean {
            return false
        }

        val encodedClass: java.lang.Class<Array<Any?>?>
            get() = Array<Any>::class.java

        @Throws(SerializationException::class, IOException::class)
        public override fun serialize(
            context: SerializationContext, nestedArray: Array<Any?>, codedOut: CodedOutputStream
        ) {
            val delay: InjectedDelay? = serializeDelays.get(nestedArray)
            if (delay != null) {
                delay.entered.countDown()
                try {
                    delay.waitFor.await()
                } catch (e: java.lang.InterruptedException) {
                    throw java.lang.AssertionError(e)
                }
            }
            val length = nestedArray.size
            codedOut.writeInt32NoTag(length)
            for (i in 0..<length) {
                val child = nestedArray[i]
                if (child is Array<Any>) {
                    codedOut.writeBoolNoTag(true)
                    context.putSharedValue(
                        child as Array<Any?>,  /* distinguisher= */null,  /* codec= */this, codedOut
                    )
                } else {
                    codedOut.writeBoolNoTag(false)
                    context.serialize(child, codedOut)
                }
            }
        }

        @Throws(SerializationException::class, IOException::class)
        public override fun deserializeDeferred(
            context: AsyncDeserializationContext, codedIn: CodedInputStream
        ): DeferredValue<Array<Any?>?> {
            val length: Int = codedIn.readInt32()
            if (length == 0) {
                return DeferredValue { EMPTY_CONTENTS }
            }
            val values = arrayOfNulls<Any>(length)
            for (i in 0..<length) {
                val indexForCapture = i
                if (codedIn.readBool()) {
                    context.getSharedValue(
                        codedIn,  /* distinguisher= */
                        null,  /* codec= */
                        this,
                        values,
                        { array, value -> array[indexForCapture] = value })
                } else {
                    context.deserialize(codedIn, values, { array, value -> array[indexForCapture] = value })
                }
            }
            return DeferredValue { values }
        }

        /**
         * Injects a controllable delay when serializing the specified `nestedArray`.
         * 
         * @param entered [CountDownLatch.countDown] called on this latch when serialization of
         * `nestedArray` is requested. This countdown occurs before the wait and used by the
         * caller for coordination.
         * @param waitFor [CountDownLatch.await] is called on this latch to inject the delay (only
         * after calling `entered.countDown()`)
         */
        fun injectSerializeDelay(
            nestedArray: Array<Any?>?, entered: CountDownLatch, waitFor: CountDownLatch
        ) {
            serializeDelays.put(nestedArray, InjectedDelay(entered, waitFor))
        }
    }

    private class InjectedDelay(entered: CountDownLatch, waitFor: CountDownLatch) {
        private val entered: CountDownLatch
        private val waitFor: CountDownLatch

        init {
            this.entered = entered
            this.waitFor = waitFor
        }
    }

    companion object {
        private val EMPTY_CONTENTS = arrayOfNulls<Any>(0)

        private const val MAX_RANDOM_ELEMENTS = 5

        /** Helper for constructing contents or nested contents.  */
        fun createRandomLeafArray(
            rng: Random,
            elementFactory: java.util.function.Function<Random?, Any?>
        ): Array<Any?> {
            val count: Int = rng.nextInt(MAX_RANDOM_ELEMENTS - 1) + 1
            val array = arrayOfNulls<Any>(count)
            for (i in 0..<count) {
                array[i] = elementFactory.apply(rng)
            }
            return array
        }

        /**
         * Edge density parameter.
         * 
         * 
         * Graph construction always selects for every node in layer N+1 some parent node in layer N.
         * After that, additional random edges are added. For any pair of nodes (x, y) in layers M, N with
         * `M < N`, this is the probability that x is an additional parent of y.
         */
        private const val EXTRA_PARENT_PROBABILITY = 0.01

        fun createRandom(
            rng: Random,
            maxLayers: Int,
            maxNodesPerLayer: Int,
            elementFactory: java.util.function.Function<Random?, Any?>
        ): NotNestedSet {
            // Creates a random DAG layer by layer.
            val layerCount: Int = rng.nextInt(maxLayers - 1) + 1

            // First creates the nodes of each layer.
            val layers: java.util.ArrayList<java.util.ArrayList<NodeBuilder>> =
                java.util.ArrayList<java.util.ArrayList<NodeBuilder>>(layerCount)
            for (i in 0..<layerCount) {
                val nodeCount: Int = rng.nextInt(maxNodesPerLayer - 1) + 1
                val layer: java.util.ArrayList<NodeBuilder?> = java.util.ArrayList<NodeBuilder?>(nodeCount)
                for (j in 0..<nodeCount) {
                    layer.add(NodeBuilder())
                }
                layers.add(layer)
            }

            // Nexts populates edges.
            for (i in layerCount - 1 downTo 1) {
                val parentLayer: java.util.ArrayList<NodeBuilder?> = layers.get(i - 1)
                val parentLayerSize: Int = parentLayer.size()
                val childLayerSize: Int = layers.get(i).size()

                for (childIndex in 0..<childLayerSize) {
                    // Ensures that every node has at least one parent in the previous layer (except the top
                    // layer).
                    parentLayer.get(rng.nextInt(parentLayerSize)).addChild(i, childIndex)

                    // For every previous node in every previous layer, possibly adds a random edge.
                    for (previousLayer in 0..<i) {
                        for (builder in layers.get(previousLayer)) {
                            if (rng.nextDouble() < EXTRA_PARENT_PROBABILITY) {
                                builder.addChild(i, childIndex)
                            }
                        }
                    }
                }
            }

            // Uses the layers to build the result, bottom-up.
            for (i in layerCount - 1 downTo 0) {
                val layer: java.util.ArrayList<NodeBuilder> = layers.get(i)
                for (builder in layer) {
                    if (i == layerCount - 1) {
                        builder.value = createRandomLeafArray(rng, elementFactory)
                        continue
                    }
                    // Inserts additional random elements into each non-leaf node.
                    val randomElementCount: Int = rng.nextInt(MAX_RANDOM_ELEMENTS)
                    val values: java.util.ArrayList<Any?> =
                        java.util.ArrayList<Any?>(builder.children.size() + randomElementCount)
                    for (child in builder.children) {
                        values.add(layers.get(child.layer).get(child.index).value)
                    }
                    for (j in 0..<randomElementCount) {
                        values.add(elementFactory.apply(rng))
                    }
                    Collections.shuffle(values, rng)
                    builder.value = values.toArray<Any?>(arrayOfNulls<Any>(0))
                }
            }

            val topLayer: java.util.ArrayList<NodeBuilder?> = layers.get(0)
            val root = arrayOfNulls<Any>(topLayer.size())
            for (i in topLayer.indices) {
                root[i] = topLayer.get(i).value
            }
            return NotNestedSet(root)
        }

        private fun setContents(set: NotNestedSet, contents: Any?) {
            set.contents = contents as Array<Any?>?
        }
    }
}
