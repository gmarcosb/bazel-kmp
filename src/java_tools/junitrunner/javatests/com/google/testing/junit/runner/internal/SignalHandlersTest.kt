// Copyright 2010 The Bazel Authors. All Rights Reserved.
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
package com.google.testing.junit.runner.internal

import com.google.common.truth.Truth
import com.google.testing.junit.runner.internal.SignalHandlers
import com.google.testing.junit.runner.internal.SignalHandlers.HandlerInstaller
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.Mockito

/**
 * Tests for SignalHandlers.
 */
@RunWith(JUnit4::class)
class SignalHandlersTest {
    private val fakeSignalInstaller = FakeSignalInstaller()
    private val signalHandlers: SignalHandlers = SignalHandlers(fakeSignalInstaller)

    internal class FakeSignalInstaller : HandlerInstaller {
        private var currentHandler: sun.misc.SignalHandler? = null

        override fun install(signal: sun.misc.Signal?, handler: sun.misc.SignalHandler?): sun.misc.SignalHandler? {
            val previousHandler: sun.misc.SignalHandler? = currentHandler
            Truth.assertWithMessage("This fake only supports the TERM signal")
                .that(signal)
                .isEqualTo(TERM_SIGNAL)
            currentHandler = handler
            return previousHandler
        }

        fun sendSignal() {
            currentHandler.handle(TERM_SIGNAL)
        }
    }

    @org.junit.Test
    fun testHandlersCanBeChained() {
        val handler1: sun.misc.SignalHandler? =
            Mockito.mock<sun.misc.SignalHandler?>(sun.misc.SignalHandler::class.java)
        val handler2: sun.misc.SignalHandler? =
            Mockito.mock<sun.misc.SignalHandler?>(sun.misc.SignalHandler::class.java)

        signalHandlers.installHandler(TERM_SIGNAL, handler1)
        signalHandlers.installHandler(TERM_SIGNAL, handler2)
        fakeSignalInstaller.sendSignal()

        Mockito.verify<sun.misc.SignalHandler?>(handler1).handle(Mockito.eq<sun.misc.Signal?>(TERM_SIGNAL))
        Mockito.verify<sun.misc.SignalHandler?>(handler2).handle(Mockito.eq<sun.misc.Signal?>(TERM_SIGNAL))
    }

    @org.junit.Test
    fun testOneHandlerCanHandleSignal() {
        val handler: sun.misc.SignalHandler? = Mockito.mock<sun.misc.SignalHandler?>(sun.misc.SignalHandler::class.java)

        signalHandlers.installHandler(TERM_SIGNAL, handler)
        fakeSignalInstaller.sendSignal()

        Mockito.verify<sun.misc.SignalHandler?>(handler).handle(Mockito.eq<sun.misc.Signal?>(TERM_SIGNAL))
    }

    companion object {
        private val TERM_SIGNAL: sun.misc.Signal = sun.misc.Signal("TERM")
    }
}
