// Copyright 2020 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.cmdline.Label

/**
 * Tests for [Event] serialization.
 * 
 * 
 * At the time of this test class's writing there is no custom EventCodec. However, event
 * property value insertion order should not affect events' serialized representation, and this
 * tests for that.
 */
@RunWith(JUnit4::class)
class EventTest {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun smoke() {
        val propertylessEvent: com.google.devtools.build.lib.events.Event? =
            com.google.devtools.build.lib.events.Event.of(
                com.google.devtools.build.lib.events.EventKind.INFO,
                "myMessage"
            )
        val byteArrayEvent: com.google.devtools.build.lib.events.Event? = com.google.devtools.build.lib.events.Event.of(
            com.google.devtools.build.lib.events.EventKind.INFO,
            "myMessage".getBytes(java.nio.charset.StandardCharsets.UTF_8)
        )
        val labelEvent: com.google.devtools.build.lib.events.Event =
            com.google.devtools.build.lib.events.Event.of(
                com.google.devtools.build.lib.events.EventKind.WARNING,
                "myOtherMessage",
                Label::class.java,
                Label.create("myPackage", "myTarget")
            )
        val labelStringEvent: com.google.devtools.build.lib.events.Event? =
            labelEvent.withProperty<String?>(String::class.java, "myTag")

        SerializationTester(propertylessEvent, byteArrayEvent, labelEvent, labelStringEvent)
            .runTests()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun serializationIsPropertyOrderAgnostic() {
        val labelStringEvent: com.google.devtools.build.lib.events.Event? =
            com.google.devtools.build.lib.events.Event.of(
                com.google.devtools.build.lib.events.EventKind.WARNING,
                "myMessage"
            )
                .withProperty<T?>(Label::class.java, Label.create("myPackage", "myTarget"))
                .withProperty<String?>(String::class.java, "myTag")

        val stringLabelEvent: com.google.devtools.build.lib.events.Event? =
            com.google.devtools.build.lib.events.Event.of(
                com.google.devtools.build.lib.events.EventKind.WARNING,
                "myMessage"
            )
                .withProperty<String?>(String::class.java, "myTag")
                .withProperty<T?>(Label::class.java, Label.create("myPackage", "myTarget"))

        val codecs: ObjectCodecs = ObjectCodecs(AutoRegistry.get())
        assertThat(codecs.serialize(labelStringEvent)).isEqualTo(codecs.serialize(stringLabelEvent))
    }
}
