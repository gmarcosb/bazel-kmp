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
package com.google.devtools.build.lib.skyframe.actiongraph.v2

import com.google.common.base.Preconditions
import com.google.devtools.build.lib.analysis.AnalysisProtosV2.Action
import java.io.OutputStream
import java.util.concurrent.Callable
import kotlin.collections.ArrayList

/** Manages the various streamed output channels of aquery. This does not support JSON format.  */
class StreamedConsumingOutputHandler(
    private val outputType: AqueryOutputHandler.OutputType,
    outputStream: OutputStream,
    codedOutputStream: CodedOutputStream,
    printStream: PrintStream,
    queue: BlockingQueue<PrintTask>
) : AqueryConsumingOutputHandler {
    private val outputStream: OutputStream
    private val codedOutputStream: CodedOutputStream
    private val printStream: PrintStream

    private val exitLock = Any()

    @kotlin.concurrent.Volatile
    private var readyToExit = false
    private val queue: BlockingQueue<PrintTask>

    init {
        Preconditions.checkArgument(
            outputType == AqueryOutputHandler.OutputType.BINARY || outputType == AqueryOutputHandler.OutputType.DELIMITED_BINARY || outputType == AqueryOutputHandler.OutputType.TEXT,
            "Only proto, streamed_proto and textproto outputs should be streamed."
        )
        this.outputStream = outputStream
        this.codedOutputStream = codedOutputStream
        this.printStream = printStream
        this.queue = queue
    }

    override fun outputArtifact(message: Artifact?) {
        addTaskToQueue(message, ActionGraphContainer.ARTIFACTS_FIELD_NUMBER, "artifacts")
    }

    override fun outputAction(message: Action?) {
        addTaskToQueue(message, ActionGraphContainer.ACTIONS_FIELD_NUMBER, "actions")
    }

    override fun outputTarget(message: Target?) {
        addTaskToQueue(message, ActionGraphContainer.TARGETS_FIELD_NUMBER, "targets")
    }

    override fun outputDepSetOfFiles(message: DepSetOfFiles?) {
        addTaskToQueue(message, ActionGraphContainer.DEP_SET_OF_FILES_FIELD_NUMBER, "dep_set_of_files")
    }

    override fun outputConfiguration(message: Configuration?) {
        addTaskToQueue(message, ActionGraphContainer.CONFIGURATION_FIELD_NUMBER, "configuration")
    }

    override fun outputAspectDescriptor(message: AspectDescriptor?) {
        addTaskToQueue(
            message, ActionGraphContainer.ASPECT_DESCRIPTORS_FIELD_NUMBER, "aspect_descriptors"
        )
    }

    override fun outputRuleClass(message: RuleClass?) {
        addTaskToQueue(message, ActionGraphContainer.RULE_CLASSES_FIELD_NUMBER, "rule_classes")
    }

    override fun outputPathFragment(message: PathFragment?) {
        addTaskToQueue(message, ActionGraphContainer.PATH_FRAGMENTS_FIELD_NUMBER, "path_fragments")
    }

    override fun startConsumer(): Callable<Void?> {
        return AqueryOutputTaskConsumer(queue)
    }

    @Throws(InterruptedException::class)
    override fun stopConsumer(discardRemainingTasks: Boolean) {
        if (discardRemainingTasks) {
            queue.drainTo(ArrayList<PrintTask?>())
        }
        // This lock ensures that the method actually waits until the consumer properly exits,
        // which prevents a race condition with the #close() method below.
        synchronized(exitLock) {
            queue.put(POISON_PILL)
            while (!readyToExit) {
                (exitLock as Object).wait()
            }
        }
    }

    /** Construct the printing task and put it in the queue.  */
    fun addTaskToQueue(message: Message?, fieldNumber: Int, messageLabel: String?) {
        // This means that there was an exception in the consumer.
        if (readyToExit) {
            return
        }
        val task: PrintTask?
        when (outputType) {
            AqueryOutputHandler.OutputType.BINARY -> task = ProtoPrintTask.Companion.create(message, fieldNumber)
            AqueryOutputHandler.OutputType.DELIMITED_BINARY -> task =
                StreamedProtoPrintTask.Companion.create(message, fieldNumber)

            AqueryOutputHandler.OutputType.TEXT -> task = TextProtoPrintTask.Companion.create(message, messageLabel)
            else -> throw IllegalStateException("Unknown outputType: " + outputType)
        }
        try {
            queue.put(task)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    @Throws(IOException::class)
    override fun close() {
        when (outputType) {
            AqueryOutputHandler.OutputType.BINARY -> codedOutputStream.flush()
            AqueryOutputHandler.OutputType.DELIMITED_BINARY -> outputStream.flush()
            AqueryOutputHandler.OutputType.TEXT -> printStream.flush()
            else -> throw IllegalStateException("Unknown outputType: " + outputType)
        }
    }

    // Only runs on 1 single thread.
    private inner class AqueryOutputTaskConsumer(queue: BlockingQueue<PrintTask>) : Callable<Void?> {
        private val queue: BlockingQueue<PrintTask>

        init {
            this.queue = queue
        }

        @Throws(InterruptedException::class, IOException::class)
        override fun call(): Void? {
            try {
                while (true) {
                    val nextTask = queue.take()

                    if (nextTask == POISON_PILL) {
                        synchronized(exitLock) {
                            readyToExit = true
                            (exitLock as Object).notify()
                        }
                        return null
                    }
                    when (outputType) {
                        AqueryOutputHandler.OutputType.BINARY -> ProtoPrintTask.Companion.print(
                            codedOutputStream,
                            nextTask as ProtoPrintTask
                        )

                        AqueryOutputHandler.OutputType.DELIMITED_BINARY -> StreamedProtoPrintTask.Companion.print(
                            outputStream,
                            nextTask as StreamedProtoPrintTask
                        )

                        AqueryOutputHandler.OutputType.TEXT -> TextProtoPrintTask.Companion.print(
                            printStream,
                            nextTask as TextProtoPrintTask
                        )

                        else -> throw IllegalStateException("Unknown outputType " + outputType.formatName())
                    }
                }
            } finally {
                // In case of an exception.
                readyToExit = true
            }
        }
    }

    companion object {
        val POISON_PILL: PrintTask = ProtoPrintTask.Companion.create(null, 0)
    }
}
