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

import com.google.devtools.build.lib.analysis.AnalysisProtosV2.Action
import java.io.OutputStream

/**
 * Represent a task to be consumed by a [AqueryConsumingOutputHandler].
 * 
 * 
 * We have separate Proto/TextProto subclasses to reduce some memory waste: we'll never need both
 * the fieldNumber and the messageLabel in a PrintTask.
 */
interface PrintTask {
    /** A task for the proto format.  */
    @AutoValue
    class ProtoPrintTask : PrintTask {
        abstract fun message(): Message?

        abstract fun fieldNumber(): Int

        companion object {
            fun create(message: Message?, fieldNumber: Int): ProtoPrintTask {
                return AutoValue_PrintTask_ProtoPrintTask(message, fieldNumber)
            }

            @Throws(IOException::class)
            fun print(codedOutputStream: CodedOutputStream, task: ProtoPrintTask) {
                print(codedOutputStream, task.message(), task.fieldNumber())
            }

            @Throws(IOException::class)
            fun print(codedOutputStream: CodedOutputStream, message: Message?, fieldNumber: Int) {
                codedOutputStream.writeMessage(fieldNumber, message)
            }
        }
    }

    /** A task for the streamed_proto format.  */
    @AutoValue
    class StreamedProtoPrintTask : PrintTask {
        abstract fun message(): Message?

        abstract fun fieldNumber(): Int

        companion object {
            fun create(message: Message?, fieldNumber: Int): StreamedProtoPrintTask {
                return AutoValue_PrintTask_StreamedProtoPrintTask(message, fieldNumber)
            }

            @Throws(IOException::class)
            fun print(out: OutputStream?, task: StreamedProtoPrintTask) {
                print(out, task.message(), task.fieldNumber())
            }

            @Throws(IOException::class)
            fun print(out: OutputStream?, message: Message?, fieldNumber: Int) {
                val builder: ActionGraphContainer.Builder = ActionGraphContainer.newBuilder()
                when (fieldNumber) {
                    ActionGraphContainer.ARTIFACTS_FIELD_NUMBER -> builder.addArtifacts(message as Artifact?)
                    ActionGraphContainer.ACTIONS_FIELD_NUMBER -> builder.addActions(message as Action?)
                    ActionGraphContainer.TARGETS_FIELD_NUMBER -> builder.addTargets(message as Target?)
                    ActionGraphContainer.DEP_SET_OF_FILES_FIELD_NUMBER -> builder.addDepSetOfFiles(message as DepSetOfFiles?)
                    ActionGraphContainer.CONFIGURATION_FIELD_NUMBER -> builder.addConfiguration(message as Configuration?)
                    ActionGraphContainer.ASPECT_DESCRIPTORS_FIELD_NUMBER -> builder.addAspectDescriptors(message as AspectDescriptor?)
                    ActionGraphContainer.RULE_CLASSES_FIELD_NUMBER -> builder.addRuleClasses(message as RuleClass?)
                    ActionGraphContainer.PATH_FRAGMENTS_FIELD_NUMBER -> builder.addPathFragments(message as PathFragment?)
                    else -> throw IllegalStateException(
                        "Unknown ActionGraphContainer field number " + fieldNumber
                    )
                }
                builder.build().writeDelimitedTo(out)
            }
        }
    }

    /** A task for the textproto format.  */
    @AutoValue
    class TextProtoPrintTask : PrintTask {
        abstract fun message(): Message?

        abstract fun messageLabel(): String?

        companion object {
            fun create(message: Message?, messageLabel: String?): TextProtoPrintTask {
                return AutoValue_PrintTask_TextProtoPrintTask(message, messageLabel)
            }

            fun print(printStream: PrintStream, task: TextProtoPrintTask) {
                print(printStream, task.message(), task.messageLabel())
            }

            fun print(printStream: PrintStream, message: Message?, messageLabel: String?) {
                printStream.print(messageLabel + " {\n" + message + "}\n")
            }
        }
    }
}
