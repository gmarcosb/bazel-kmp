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
package com.google.devtools.build.lib.events

import net.starlark.java.eval.Mutability

/** Tests for [Event].  */
@RunWith(JUnit4::class)
class EventTest {
    @org.junit.Test
    fun eventKindMessage() {
        val event: com.google.devtools.build.lib.events.Event = com.google.devtools.build.lib.events.Event.of(
            com.google.devtools.build.lib.events.EventKind.WARNING,
            "myMessage"
        )

        Truth.assertThat(event.getMessage()).isEqualTo("myMessage")
        Truth.assertThat<com.google.devtools.build.lib.events.EventKind?>(event.getKind())
            .isEqualTo(com.google.devtools.build.lib.events.EventKind.WARNING)
    }

    @org.junit.Test
    fun eventMessageEncoding() {
        val message = "Bazel \u1f33f"

        val stringEvent: com.google.devtools.build.lib.events.Event = com.google.devtools.build.lib.events.Event.of(
            com.google.devtools.build.lib.events.EventKind.WARNING,
            message
        )
        val stringEvent2: com.google.devtools.build.lib.events.Event = com.google.devtools.build.lib.events.Event.of(
            com.google.devtools.build.lib.events.EventKind.WARNING,
            "Bazel \u1f33f"
        )
        Truth.assertThat(stringEvent.getMessage()).isEqualTo(message)
        Truth.assertThat(stringEvent.getMessageBytes())
            .isEqualTo(message.toByteArray(java.nio.charset.StandardCharsets.UTF_8))

        val byteArrayEvent: com.google.devtools.build.lib.events.Event = com.google.devtools.build.lib.events.Event.of(
            com.google.devtools.build.lib.events.EventKind.WARNING,
            message.toByteArray(java.nio.charset.StandardCharsets.UTF_8)
        )
        val byteArrayEvent2: com.google.devtools.build.lib.events.Event = com.google.devtools.build.lib.events.Event.of(
            com.google.devtools.build.lib.events.EventKind.WARNING,
            "Bazel \u1f33f".toByteArray(java.nio.charset.StandardCharsets.UTF_8)
        )
        Truth.assertThat(byteArrayEvent.getMessage()).isEqualTo(message)
        Truth.assertThat(byteArrayEvent.getMessageBytes())
            .isEqualTo(message.toByteArray(java.nio.charset.StandardCharsets.UTF_8))

        EqualsTester()
            .addEqualityGroup(stringEvent, stringEvent2)
            .addEqualityGroup(byteArrayEvent, byteArrayEvent2)
            .testEquals()
    }

    @org.junit.Test
    fun eventLocationSensitiveToString() {
        val file = "/path/to/workspace/my/sample/path.txt"
        val location: net.starlark.java.syntax.Location? =
            net.starlark.java.syntax.Location.fromFileLineColumn(file, 3, 4)
        val event: com.google.devtools.build.lib.events.Event =
            com.google.devtools.build.lib.events.Event.of<net.starlark.java.syntax.Location?>(
                com.google.devtools.build.lib.events.EventKind.WARNING,
                "myMessage",
                net.starlark.java.syntax.Location::class.java,
                location
            )

        Truth.assertThat<net.starlark.java.syntax.Location?>(event.getLocation()).isEqualTo(location)
        Truth.assertThat(event.toString()).isEqualTo("WARNING " + file + ":3:4: myMessage")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun messageReference() {
        val messageBytes: ByteArray = "message".toByteArray(java.nio.charset.StandardCharsets.UTF_8)
        val event: com.google.devtools.build.lib.events.Event = com.google.devtools.build.lib.events.Event.of(
            com.google.devtools.build.lib.events.EventKind.WARNING,
            messageBytes
        )
        Truth.assertThat(event.getMessageBytes()).isEqualTo(messageBytes)
    }

    @org.junit.Test
    fun noProperties() {
        val event: com.google.devtools.build.lib.events.Event = com.google.devtools.build.lib.events.Event.of(
            com.google.devtools.build.lib.events.EventKind.WARNING,
            "myMessage"
        )
        Truth.assertThat(event.getProperty<Any?>(Any::class.java)).isNull()
        Truth.assertThat(event).isEqualTo(
            com.google.devtools.build.lib.events.Event.of(
                com.google.devtools.build.lib.events.EventKind.WARNING,
                "myMessage"
            )
        )
    }

    @org.junit.Test
    fun oneProperty() {
        val event: com.google.devtools.build.lib.events.Event = com.google.devtools.build.lib.events.Event.of<String?>(
            com.google.devtools.build.lib.events.EventKind.WARNING,
            "myMessage",
            String::class.java,
            "myProperty"
        )
        Truth.assertThat(event.getProperty<Any?>(Any::class.java)).isNull()
        Truth.assertThat(event.getProperty<String?>(String::class.java)).isEqualTo("myProperty")
        Truth.assertThat(event)
            .isEqualTo(
                com.google.devtools.build.lib.events.Event.of<String?>(
                    com.google.devtools.build.lib.events.EventKind.WARNING,
                    "myMessage",
                    String::class.java,
                    "myProperty"
                )
            )
    }

    @org.junit.Test
    fun withAddedProperty() {
        val event: com.google.devtools.build.lib.events.Event = com.google.devtools.build.lib.events.Event.of<String?>(
            com.google.devtools.build.lib.events.EventKind.WARNING,
            "myMessage",
            String::class.java,
            "myProperty"
        )
        val location: net.starlark.java.syntax.Location? =
            net.starlark.java.syntax.Location.fromFileLineColumn("file", 1, 2)
        val twoPropertyEvent: com.google.devtools.build.lib.events.Event =
            event.withProperty<net.starlark.java.syntax.Location?>(
                net.starlark.java.syntax.Location::class.java,
                location
            )

        Truth.assertThat(event).isNotSameInstanceAs(twoPropertyEvent)
        Truth.assertThat(event).isNotEqualTo(twoPropertyEvent)
        Truth.assertThat(event.getProperty<String?>(String::class.java)).isEqualTo("myProperty")
        Truth.assertThat<net.starlark.java.syntax.Location?>(event.getProperty<net.starlark.java.syntax.Location?>(net.starlark.java.syntax.Location::class.java))
            .isNull()
        Truth.assertThat(twoPropertyEvent.getProperty<String?>(String::class.java)).isEqualTo("myProperty")
        Truth.assertThat<net.starlark.java.syntax.Location?>(
            twoPropertyEvent.getProperty<net.starlark.java.syntax.Location?>(
                net.starlark.java.syntax.Location::class.java
            )
        ).isSameInstanceAs(location)
    }

    @org.junit.Test
    fun withReplacedProperty() {
        val location: net.starlark.java.syntax.Location? =
            net.starlark.java.syntax.Location.fromFileLineColumn("file", 1, 2)
        val event: com.google.devtools.build.lib.events.Event =
            com.google.devtools.build.lib.events.Event.of(
                com.google.devtools.build.lib.events.EventKind.WARNING,
                "myMessage"
            )
                .withProperty<String?>(String::class.java, "myProperty")
                .withProperty<net.starlark.java.syntax.Location?>(
                    net.starlark.java.syntax.Location::class.java,
                    location
                )
        val replacedPropertyEvent: com.google.devtools.build.lib.events.Event =
            event.withProperty<String?>(String::class.java, "yourProperty")

        Truth.assertThat(event).isNotSameInstanceAs(replacedPropertyEvent)
        Truth.assertThat(event).isNotEqualTo(replacedPropertyEvent)
        Truth.assertThat(event.getProperty<String?>(String::class.java)).isEqualTo("myProperty")
        Truth.assertThat<net.starlark.java.syntax.Location?>(event.getProperty<net.starlark.java.syntax.Location?>(net.starlark.java.syntax.Location::class.java))
            .isSameInstanceAs(location)
        Truth.assertThat(replacedPropertyEvent.getProperty<String?>(String::class.java)).isEqualTo("yourProperty")
        Truth.assertThat<net.starlark.java.syntax.Location?>(
            replacedPropertyEvent.getProperty<net.starlark.java.syntax.Location?>(
                net.starlark.java.syntax.Location::class.java
            )
        ).isSameInstanceAs(location)
    }

    @org.junit.Test
    fun withRemovedProperty() {
        val location: net.starlark.java.syntax.Location? =
            net.starlark.java.syntax.Location.fromFileLineColumn("file", 1, 2)
        val event: com.google.devtools.build.lib.events.Event =
            com.google.devtools.build.lib.events.Event.of(
                com.google.devtools.build.lib.events.EventKind.WARNING,
                "myMessage"
            )
                .withProperty<String?>(String::class.java, "myProperty")
                .withProperty<net.starlark.java.syntax.Location?>(
                    net.starlark.java.syntax.Location::class.java,
                    location
                )
        val removedPropertyEvent: com.google.devtools.build.lib.events.Event =
            event.withProperty<net.starlark.java.syntax.Location?>(net.starlark.java.syntax.Location::class.java, null)

        Truth.assertThat(event).isNotSameInstanceAs(removedPropertyEvent)
        Truth.assertThat(event).isNotEqualTo(removedPropertyEvent)
        Truth.assertThat(event.getProperty<String?>(String::class.java)).isEqualTo("myProperty")
        Truth.assertThat<net.starlark.java.syntax.Location?>(event.getProperty<net.starlark.java.syntax.Location?>(net.starlark.java.syntax.Location::class.java))
            .isSameInstanceAs(location)
        Truth.assertThat(removedPropertyEvent.getProperty<String?>(String::class.java)).isEqualTo("myProperty")
        Truth.assertThat<net.starlark.java.syntax.Location?>(
            removedPropertyEvent.getProperty<net.starlark.java.syntax.Location?>(
                net.starlark.java.syntax.Location::class.java
            )
        ).isNull()
        Truth.assertThat(
            removedPropertyEvent.withProperty<net.starlark.java.syntax.Location?>(
                net.starlark.java.syntax.Location::class.java,
                null
            )
        )
            .isSameInstanceAs(removedPropertyEvent)
    }

    @org.junit.Test
    fun propertyOrderAgnostic() {
        val location: net.starlark.java.syntax.Location? =
            net.starlark.java.syntax.Location.fromFileLineColumn("file", 1, 2)
        val stringFirstEvent: com.google.devtools.build.lib.events.Event =
            com.google.devtools.build.lib.events.Event.of(
                com.google.devtools.build.lib.events.EventKind.WARNING,
                "myMessage"
            )
                .withProperty<String?>(String::class.java, "myProperty")
                .withProperty<net.starlark.java.syntax.Location?>(
                    net.starlark.java.syntax.Location::class.java,
                    location
                )
        val locationFirstEvent: com.google.devtools.build.lib.events.Event =
            com.google.devtools.build.lib.events.Event.of(
                com.google.devtools.build.lib.events.EventKind.WARNING,
                "myMessage"
            )
                .withProperty<net.starlark.java.syntax.Location?>(
                    net.starlark.java.syntax.Location::class.java,
                    location
                )
                .withProperty<String?>(String::class.java, "myProperty")
        EqualsTester().addEqualityGroup(stringFirstEvent, locationFirstEvent).testEquals()
    }

    @org.junit.Test
    fun withTag() {
        val event: com.google.devtools.build.lib.events.Event = com.google.devtools.build.lib.events.Event.of(
            com.google.devtools.build.lib.events.EventKind.WARNING,
            "myMessage"
        ).withTag("myTag")
        Truth.assertThat(event.getTag()).isEqualTo("myTag")
        Truth.assertThat(event.withTag("myTag")).isSameInstanceAs(event)

        val withoutTag: com.google.devtools.build.lib.events.Event = event.withTag(null)
        Truth.assertThat(withoutTag.getTag()).isNull()
        Truth.assertThat(withoutTag.withTag(null)).isSameInstanceAs(withoutTag)
    }

    @org.junit.Test
    fun tagIsSameAsStringProperty() {
        Truth.assertThat(
            com.google.devtools.build.lib.events.Event.of<String?>(
                com.google.devtools.build.lib.events.EventKind.WARNING,
                "myMessage",
                String::class.java,
                "myProperty"
            ).getTag()
        )
            .isEqualTo("myProperty")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testWithProcessOutput() {
        val stdOutPath = "/stdout"
        val stdOut: ByteArray = "some stdout output".toByteArray(java.nio.charset.StandardCharsets.UTF_8)
        val stdErrPath = "/stderr"
        val stdErr: ByteArray = "some stderr error".toByteArray(java.nio.charset.StandardCharsets.UTF_8)

        val testProcessOutput: ProcessOutput =
            object : ProcessOutput {
                val stdOutSize: Long
                    get() = stdOut.size.toLong()

                val stdErrSize: Long
                    get() = stdErr.size.toLong()
            }

        val event: com.google.devtools.build.lib.events.Event = com.google.devtools.build.lib.events.Event.of(
            com.google.devtools.build.lib.events.EventKind.WARNING,
            "myMessage"
        )
        val eventWithProcessOutput: com.google.devtools.build.lib.events.Event =
            event.withProcessOutput(testProcessOutput)

        Truth.assertThat(eventWithProcessOutput).isNotEqualTo(event)
        Truth.assertThat(event.getProcessOutput()).isNull()
        Truth.assertThat(eventWithProcessOutput.getProcessOutput()).isNotNull()

        Truth.assertThat(eventWithProcessOutput.getStdOut()).isEqualTo(this.stdOut)
        assertThat(eventWithProcessOutput.getProcessOutput().stdOut).isEqualTo(this.stdOut)
        assertThat(eventWithProcessOutput.getProcessOutput().stdOutSize).isEqualTo(stdOut.size)

        Truth.assertThat(eventWithProcessOutput.getStdErr()).isEqualTo(this.stdErr)
        assertThat(eventWithProcessOutput.getProcessOutput().stdErr).isEqualTo(this.stdErr)
        assertThat(eventWithProcessOutput.getProcessOutput().stdErrSize).isEqualTo(stdErr.size)
    }

    @org.junit.Test
    fun replayEventsOn() {
        val events: com.google.common.collect.ImmutableList<com.google.devtools.build.lib.events.Event?> =
            com.google.common.collect.ImmutableList.of<com.google.devtools.build.lib.events.Event?>(
                com.google.devtools.build.lib.events.Event.of(
                    com.google.devtools.build.lib.events.EventKind.INFO,
                    "someInfo"
                ),
                com.google.devtools.build.lib.events.Event.of(
                    com.google.devtools.build.lib.events.EventKind.WARNING,
                    "someWarning"
                )
            )

        val mock: com.google.devtools.build.lib.events.EventHandler? =
            Mockito.mock<com.google.devtools.build.lib.events.EventHandler?>(com.google.devtools.build.lib.events.EventHandler::class.java)

        com.google.devtools.build.lib.events.Event.replayEventsOn(mock, events)

        val inOrder: InOrder = Mockito.inOrder(mock)
        inOrder.verify<com.google.devtools.build.lib.events.EventHandler?>(mock).handle(events.get(0))
        inOrder.verify<com.google.devtools.build.lib.events.EventHandler?>(mock).handle(events.get(1))
    }

    @org.junit.Test
    fun replaySyntaxErrorsOn() {
        val location1: net.starlark.java.syntax.Location? =
            net.starlark.java.syntax.Location.fromFileLineColumn("someFile", 3, 4)
        val location2: net.starlark.java.syntax.Location? =
            net.starlark.java.syntax.Location.fromFileLineColumn("someOtherFile", 5, 6)
        val syntaxErrors: com.google.common.collect.ImmutableList<net.starlark.java.syntax.SyntaxError?> =
            com.google.common.collect.ImmutableList.of<net.starlark.java.syntax.SyntaxError?>(
                net.starlark.java.syntax.SyntaxError(location1, "message1"),
                net.starlark.java.syntax.SyntaxError(location2, "message2")
            )

        val mock: com.google.devtools.build.lib.events.EventHandler? =
            Mockito.mock<com.google.devtools.build.lib.events.EventHandler?>(com.google.devtools.build.lib.events.EventHandler::class.java)
        com.google.devtools.build.lib.events.Event.replayEventsOn(mock, syntaxErrors)

        val inOrder: InOrder = Mockito.inOrder(mock)
        inOrder
            .verify<com.google.devtools.build.lib.events.EventHandler?>(mock)
            .handle(
                com.google.devtools.build.lib.events.Event.error(
                    syntaxErrors.get(0).location(),
                    syntaxErrors.get(0).message()
                )
            )
        inOrder
            .verify<com.google.devtools.build.lib.events.EventHandler?>(mock)
            .handle(
                com.google.devtools.build.lib.events.Event.error(
                    syntaxErrors.get(1).location(),
                    syntaxErrors.get(1).message()
                )
            )
    }

    @org.junit.Test
    fun debugPrintHandler() {
        val mockHandler: com.google.devtools.build.lib.events.EventHandler? =
            Mockito.mock<com.google.devtools.build.lib.events.EventHandler?>(com.google.devtools.build.lib.events.EventHandler::class.java)
        val printHandler: PrintHandler = com.google.devtools.build.lib.events.Event.makeDebugPrintHandler(mockHandler)
        val starlarkThread: StarlarkThread? =
            StarlarkThread.createTransient(Mutability.create(), StarlarkSemantics.DEFAULT)

        printHandler.print(starlarkThread, "someMessage")

        Mockito.verify<com.google.devtools.build.lib.events.EventHandler?>(mockHandler).handle(
            com.google.devtools.build.lib.events.Event.debug(
                net.starlark.java.syntax.Location.BUILTIN,
                "someMessage"
            )
        )
    }
}
