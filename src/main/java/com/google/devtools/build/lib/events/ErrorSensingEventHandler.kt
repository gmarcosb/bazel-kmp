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

import java.util.concurrent.atomic.AtomicReference

/**
 * Passes through any events, and notes if any of them were errors. It is thread-safe as long as the
 * target eventHandler is thread-safe.
 * 
 * 
 * Optionally retains the first error event property value associated with a specified class.
 */
class ErrorSensingEventHandler<T>(
    eventHandler: com.google.devtools.build.lib.events.ExtendedEventHandler?,
    errorPropertyClass: java.lang.Class<T?>?
) : com.google.devtools.build.lib.events.DelegatingEventHandler(eventHandler) {
    private val errorPropertyClass: java.lang.Class<T?>?
    private val errorProperty: AtomicReference<T?> = AtomicReference<T?>(null)

    @kotlin.concurrent.Volatile
    private var hasErrors = false

    init {
        this.errorPropertyClass = errorPropertyClass
    }

    override fun handle(e: com.google.devtools.build.lib.events.Event) {
        if (e.getKind() == com.google.devtools.build.lib.events.EventKind.ERROR) {
            hasErrors = true
            if (errorPropertyClass != null) {
                val propertyValue: T? = e.getProperty<T?>(errorPropertyClass)
                if (propertyValue != null) {
                    errorProperty.compareAndSet( /*expect=*/null,  /*update=*/propertyValue)
                }
            }
        }
        super.handle(e)
    }

    /** Returns whether any of the events on this objects were errors.  */
    fun hasErrors(): Boolean {
        return hasErrors
    }

    /** Returns the retained error event property value, if any.  */
    fun getErrorProperty(): T? {
        return errorProperty.get()
    }

    companion object {
        fun withoutPropertyValueTracking(
            eventHandler: com.google.devtools.build.lib.events.ExtendedEventHandler?
        ): ErrorSensingEventHandler<java.lang.Void?> {
            return com.google.devtools.build.lib.events.ErrorSensingEventHandler<java.lang.Void?>(
                eventHandler,  /*errorPropertyClass=*/
                null
            )
        }
    }
}
