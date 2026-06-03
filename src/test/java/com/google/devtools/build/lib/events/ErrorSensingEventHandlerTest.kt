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

import com.google.common.truth.Truth
import com.google.devtools.build.lib.events.ErrorSensingEventHandler
import com.google.devtools.build.lib.events.ExtendedEventHandler
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.Mockito

/** Tests [ErrorSensingEventHandler].  */
@RunWith(JUnit4::class)
class ErrorSensingEventHandlerTest {
    @org.junit.Test
    fun delegation() {
        val delegate: ExtendedEventHandler? = Mockito.mock<ExtendedEventHandler?>(ExtendedEventHandler::class.java)
        val subject: ErrorSensingEventHandler<java.lang.Void?> =
            ErrorSensingEventHandler.withoutPropertyValueTracking(delegate)
        val event: com.google.devtools.build.lib.events.Event? = com.google.devtools.build.lib.events.Event.of(
            com.google.devtools.build.lib.events.EventKind.INFO,
            "message"
        )

        subject.handle(event)

        Mockito.verify<ExtendedEventHandler?>(delegate).handle(event)
    }

    @org.junit.Test
    fun rememberError() {
        val delegate: ExtendedEventHandler? = Mockito.mock<ExtendedEventHandler?>(ExtendedEventHandler::class.java)
        val subject: ErrorSensingEventHandler<java.lang.Void?> =
            ErrorSensingEventHandler.withoutPropertyValueTracking(delegate)

        subject.handle(
            com.google.devtools.build.lib.events.Event.of(
                com.google.devtools.build.lib.events.EventKind.INFO,
                "message"
            )
        )

        Truth.assertThat(subject.hasErrors()).isFalse()

        subject.handle(
            com.google.devtools.build.lib.events.Event.of(
                com.google.devtools.build.lib.events.EventKind.ERROR,
                "anError"
            )
        )

        Truth.assertThat(subject.hasErrors()).isTrue()
    }

    @org.junit.Test
    fun rememberErrorProperty() {
        val delegate: ExtendedEventHandler? = Mockito.mock<ExtendedEventHandler?>(ExtendedEventHandler::class.java)

        val withoutTracking: ErrorSensingEventHandler<java.lang.Void?> =
            ErrorSensingEventHandler.withoutPropertyValueTracking(delegate)
        val withTracking: ErrorSensingEventHandler<String?> =
            ErrorSensingEventHandler<String?>(delegate, String::class.java)

        val nonerrorEvent: com.google.devtools.build.lib.events.Event? =
            com.google.devtools.build.lib.events.Event.info("nonerror").withProperty<String?>(
                String::class.java, "propertyValue"
            )
        withoutTracking.handle(nonerrorEvent)
        withTracking.handle(nonerrorEvent)

        Truth.assertThat(withoutTracking.getErrorProperty()).isNull()
        Truth.assertThat(withTracking.getErrorProperty()).isNull()

        val errorEvent: com.google.devtools.build.lib.events.Event? =
            com.google.devtools.build.lib.events.Event.error("anError").withProperty<String?>(
                String::class.java, "propertyValue"
            )
        withoutTracking.handle(errorEvent)
        withTracking.handle(errorEvent)

        Truth.assertThat(withoutTracking.getErrorProperty()).isNull()
        Truth.assertThat(withTracking.getErrorProperty()).isEqualTo("propertyValue")

        withTracking.handle(
            com.google.devtools.build.lib.events.Event.error("anotherError").withProperty<String?>(
                String::class.java, "ignoredValue"
            )
        )
        Truth.assertThat(withTracking.getErrorProperty()).isEqualTo("propertyValue")
    }
}
