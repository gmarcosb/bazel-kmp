// Copyright 2018 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.util.io

import com.google.devtools.build.lib.concurrent.ThreadSafety

/**
 * An output stream supporting asynchronous writes of length-delimited protocol buffer messages,
 * backed by a file.
 */
@ThreadSafety.ThreadSafe
class AsynchronousMessageOutputStream<T : Message?>(name: String?, out: java.io.OutputStream) :
    MessageOutputStream<T?> {
    private val writerThread: java.lang.Thread

    // Maybe we should use an ArrayBlockingQueue instead, and accept that write may block if the
    // buffer is full?
    private val queue: BlockingQueue<ByteArray?> = LinkedBlockingDeque<ByteArray?>()

    // The future returned by closeAsync().
    private val closeFuture: com.google.common.util.concurrent.SettableFuture<java.lang.Void?> =
        com.google.common.util.concurrent.SettableFuture.create<java.lang.Void?>()

    // To store any exception raised from the writes.
    private val exception: AtomicReference<Throwable?> = AtomicReference<Throwable?>()

    constructor(path: com.google.devtools.build.lib.vfs.Path) : this(
        path.toString(),
        BufferedOutputStream( // Use a buffer of 100 kByte, scientifically chosen at random.
            path.getOutputStream(), 100000
        )
    )

    init {
        writerThread =
            java.lang.Thread(
                java.lang.Runnable {
                    try {
                        var data: ByteArray?
                        while ((queue.take().also { data = it }) != POISON_PILL) {
                            out.write(data)
                        }
                    } catch (e: java.lang.InterruptedException) {
                        // Exit quietly.
                    } catch (e: java.lang.Exception) {
                        exception.set(e)
                        closeFuture.setException(e)
                    } finally {
                        try {
                            out.close()
                            closeFuture.set(null)
                        } catch (e: java.lang.Exception) {
                            closeFuture.setException(e)
                        }
                    }
                },
                "async-file-writer:" + name
            )
        writerThread.start()
    }

    /**
     * Writes a protocol buffer message in the same format as [ ][MessageLite.writeDelimitedTo].
     * 
     * 
     * The writes are guaranteed to land in the output file in the same order that they were
     * called; However, some writes may fail, leaving the file partially corrupted. In case a write
     * fails, an exception will be propagated in close, but remaining writes will be allowed to
     * continue.
     */
    override fun write(m: T?) {
        com.google.common.base.Preconditions.checkNotNull<T?>(m)

        if (closeFuture.isDone()) {
            if (exception.get() != null) {
                // There was a previous write failure. Silently return without doing anything.
                return
            } else {
                // Attempted to write after closing.
                throw java.lang.IllegalStateException()
            }
        }

        val size: Int = m.getSerializedSize()
        val bos: java.io.ByteArrayOutputStream =
            java.io.ByteArrayOutputStream(CodedOutputStream.computeUInt32SizeNoTag(size) + size)
        try {
            m.writeDelimitedTo(bos)
        } catch (e: IOException) {
            // This should never happen with an in-memory stream.
            exception.compareAndSet(null, java.lang.IllegalStateException(e.toString()))
            return
        }

        com.google.common.util.concurrent.Uninterruptibles.putUninterruptibly<ByteArray?>(queue, bos.toByteArray())
    }

    /**
     * Closes the stream and blocks until all pending writes are completed.
     * 
     * Throws an exception if any of the writes or the close itself have failed.
     */
    @Throws(IOException::class)
    override fun close() {
        try {
            closeAsync().get()
        } catch (e: java.lang.InterruptedException) {
            java.lang.Thread.currentThread().interrupt()
        } catch (e: ExecutionException) {
            com.google.common.base.Throwables.throwIfInstanceOf<IOException?>(e.cause, IOException::class.java)
            com.google.common.base.Throwables.throwIfInstanceOf<java.lang.RuntimeException?>(
                e.cause,
                java.lang.RuntimeException::class.java
            )
            throw java.lang.RuntimeException(e.cause)
        }
    }

    /**
     * Returns a future that will close the stream when all pending writes are completed.
     * 
     * Any failed writes will propagate an exception.
     */
    fun closeAsync(): com.google.common.util.concurrent.ListenableFuture<java.lang.Void?> {
        com.google.common.util.concurrent.Uninterruptibles.putUninterruptibly<ByteArray?>(queue, POISON_PILL)
        return closeFuture
    }

    companion object {
        private val POISON_PILL = ByteArray(1)
    }
}
