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

import com.google.testing.junit.runner.junit4.JUnit4TestModelBuilder.get
import java.util.concurrent.atomic.AtomicReference

/** Helper class to install signal handlers.  */
// no alternative for signal handling?
open class SignalHandlers(private val handlerInstaller: HandlerInstaller) {
    /**
     * Adds the given signal handler to the existing ones.
     * 
     * 
     * Signal handlers are responsible to catch any exception if the following
     * handlers need to be executed when a handler throws an exception.
     * 
     * @param signal The signal to handle.
     * @param signalHandler The handler to install.
     */
    open fun installHandler(signal: sun.misc.Signal?, signalHandler: sun.misc.SignalHandler) {
        val previousHandlerReference: AtomicReference<sun.misc.SignalHandler?> =
            AtomicReference<sun.misc.SignalHandler?>()
        previousHandlerReference.set(handlerInstaller.install(signal, object : sun.misc.SignalHandler {
            override fun handle(signal: sun.misc.Signal?) {
                signalHandler.handle(signal)
                val previousHandler: sun.misc.SignalHandler? = previousHandlerReference.get()
                if (previousHandler != null) {
                    previousHandler.handle(signal)
                }
            }
        }))
    }

    /**
     * Wraps sun.misc.Signal#handle(sun.misc.Signal, sun.misc.SignalHandler)
     * to help with testing.
     */
    interface HandlerInstaller {
        /**
         * @see sun.misc.Signal.handle
         */
        fun install(signal: sun.misc.Signal?, handler: sun.misc.SignalHandler?): sun.misc.SignalHandler?
    }

    companion object {
        /**
         * Creates a handler installer that installs signal handlers.
         */
        fun createRealHandlerInstaller(): HandlerInstaller {
            return object : HandlerInstaller {
                override fun install(
                    signal: sun.misc.Signal,
                    handler: sun.misc.SignalHandler?
                ): sun.misc.SignalHandler? {
                    return sun.misc.Signal.handle(signal, handler)
                }
            }
        }
    }
}
