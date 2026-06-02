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
package com.google.devtools.build.lib.concurrent

import java.util.AbstractQueue
import java.util.concurrent.BlockingDeque
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit

/** A [BlockingQueue] with LIFO (last-in-first-out) ordering.  */
class BlockingStack<E> : AbstractQueue<E?>(), BlockingQueue<E?> {
    // We just restrict to only using the *First methods on the deque, turning it into a stack.
    private val deque: BlockingDeque<E?>

    init {
        this.deque = LinkedBlockingDeque<E?>()
    }

    override fun iterator(): MutableIterator<E?>? {
        return deque.iterator()
    }

    override fun size(): Int {
        return deque.size()
    }

    @Throws(java.lang.InterruptedException::class)
    override fun put(e: E?) {
        deque.putFirst(e)
    }

    @Throws(java.lang.InterruptedException::class)
    override fun offer(e: E?, timeout: Long, unit: TimeUnit?): Boolean {
        return deque.offerFirst(e, timeout, unit)
    }

    override fun offer(e: E?): Boolean {
        return deque.offerFirst(e)
    }

    @Throws(java.lang.InterruptedException::class)
    override fun take(): E? {
        return deque.takeFirst()
    }

    @Throws(java.lang.InterruptedException::class)
    override fun poll(timeout: Long, unit: TimeUnit?): E? {
        return deque.pollFirst(timeout, unit)
    }

    override fun poll(): E? {
        return deque.pollFirst()
    }

    override fun remainingCapacity(): Int {
        return deque.remainingCapacity()
    }

    override fun drainTo(c: MutableCollection<in E?>?): Int {
        return deque.drainTo(c)
    }

    override fun drainTo(c: MutableCollection<in E?>?, maxElements: Int): Int {
        return deque.drainTo(c, maxElements)
    }

    override fun peek(): E? {
        return deque.peekFirst()
    }
}
