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
package com.google.devtools.build.lib.events.util

import com.google.devtools.build.lib.events.EventBusEventHandler

/**
 * An apparatus for reporting / collecting events.
 */
class EventCollectionApparatus @kotlin.jvm.JvmOverloads constructor(mask: MutableSet<com.google.devtools.build.lib.events.EventKind?>? = com.google.devtools.build.lib.events.EventKind.ERRORS_WARNINGS_AND_INFO) {
    private val eventCollector: EventCollector
    private val reporter: com.google.devtools.build.lib.events.Reporter
    private val printingEventHandler: PrintingEventHandler

    private var failFast = false
    private val handlers: MutableList<com.google.devtools.build.lib.events.EventHandler?> =
        java.util.ArrayList<com.google.devtools.build.lib.events.EventHandler?>()

    fun clear() {
        eventCollector.clear()
    }

    fun initExternal(reporter: com.google.devtools.build.lib.events.Reporter) {
        // TODO(ulfjack): Changes to the EventCollectionApparatus are not reflected in the external
        // reporter, i.e., this is a one-shot change. Maybe we should store the external reporter here?
        reporter.addHandler(eventCollector)
        reporter.addHandler(printingEventHandler)
        for (handler in handlers) {
            reporter.addHandler(handler)
        }
        if (failFast) {
            reporter.addHandler(FAIL_FAST_HANDLER)
        }
    }

    /**
     * Determine whether the {#link reporter()} created by this apparatus will
     * fail fast, that is, throw an exception whenever we encounter an event of
     * matching [EventKind.ERRORS_AND_WARNINGS].
     * Default: `true`.
     */
    fun setFailFast(failFast: Boolean) {
        this.failFast = failFast
        if (failFast) {
            reporter.addHandler(FAIL_FAST_HANDLER)
        } else {
            reporter.removeHandler(FAIL_FAST_HANDLER)
        }
    }

    fun addHandler(eventHandler: com.google.devtools.build.lib.events.EventHandler?) {
        reporter.addHandler(eventHandler)
        handlers.add(eventHandler)
    }

    /** An exception thrown by [.FAIL_FAST_HANDLER].  */ // TODO(bazel-team): Possibly extend RuntimeException instead of IllegalArgumentException.
    class FailFastException(s: String?) : java.lang.IllegalArgumentException(s)

    /**
     * Determine which events the [.collector] created by this apparatus
     * will collect. Default: [EventKind.ERRORS_AND_WARNINGS].
     */
    init {
        eventCollector = EventCollector(mask)
        printingEventHandler =
            PrintingEventHandler(com.google.devtools.build.lib.events.EventKind.ERRORS_AND_WARNINGS_AND_OUTPUT)
        reporter =
            com.google.devtools.build.lib.events.Reporter(
                EventBusEventHandler.createWithNewEventBus(), eventCollector, printingEventHandler
            )
        this.setFailFast(true)
    }

    /**
     * @return the event reporter for this apparatus
     */
    fun reporter(): com.google.devtools.build.lib.events.Reporter {
        return reporter
    }

    /**
     * @return the event collector for this apparatus.
     */
    fun collector(): EventCollector {
        return eventCollector
    }

    fun infos(): Iterable<com.google.devtools.build.lib.events.Event?>? {
        return eventCollector.filtered(com.google.devtools.build.lib.events.EventKind.INFO)
    }

    fun errors(): Iterable<com.google.devtools.build.lib.events.Event?>? {
        return eventCollector.filtered(com.google.devtools.build.lib.events.EventKind.ERROR)
    }

    fun warnings(): Iterable<com.google.devtools.build.lib.events.Event?>? {
        return eventCollector.filtered(com.google.devtools.build.lib.events.EventKind.WARNING)
    }

    /**
     * Redirects all output to the specified OutErr stream pair.
     * Returns the previous OutErr.
     */
    fun setOutErr(outErr: OutErr?): OutErr? {
        return printingEventHandler.setOutErr(outErr)
    }

    /**
     * Utility method: Asserts that the [.collector] has not collected
     * any warnings or errors.
     */
    fun assertNoWarningsOrErrors() {
        MoreAsserts.assertNoEvents(warnings())
        MoreAsserts.assertNoEvents(errors())
    }

    fun assertNoWarnings() {
        MoreAsserts.assertNoEvents(warnings())
    }

    /**
     * Utility method: Assert that the [.collector] has received an info message with the
     * `expectedMessage`.
     */
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun assertContainsInfo(expectedMessage: String?): com.google.devtools.build.lib.events.Event? {
        return MoreAsserts.assertContainsEvent(
            eventCollector,
            expectedMessage,
            com.google.devtools.build.lib.events.EventKind.INFO
        )
    }

    /**
     * Utility method: Assert that the [.collector] has received an error with the `expectedMessage`.
     */
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun assertContainsError(expectedMessage: String?): com.google.devtools.build.lib.events.Event? {
        return MoreAsserts.assertContainsEvent(
            eventCollector,
            expectedMessage,
            com.google.devtools.build.lib.events.EventKind.ERROR
        )
    }

    /**
     * Utility method: Assert that the [.collector] has received an error that matches `expectedPattern`.
     */
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun assertContainsError(expectedPattern: java.util.regex.Pattern?): com.google.devtools.build.lib.events.Event? {
        return MoreAsserts.assertContainsEvent(
            eventCollector,
            expectedPattern,
            com.google.devtools.build.lib.events.EventKind.ERROR
        )
    }

    /**
     * Utility method: Assert that the [.collector] has received a warning with the `expectedMessage`.
     */
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun assertContainsWarning(expectedMessage: String?): com.google.devtools.build.lib.events.Event? {
        return MoreAsserts.assertContainsEvent(
            eventCollector,
            expectedMessage,
            com.google.devtools.build.lib.events.EventKind.WARNING
        )
    }

    /**
     * Utility method: Assert that the [.collector] has received a warning that matches `expectedPattern`.
     */
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun assertContainsWarning(expectedPattern: java.util.regex.Pattern?): com.google.devtools.build.lib.events.Event? {
        return MoreAsserts.assertContainsEvent(
            eventCollector,
            expectedPattern,
            com.google.devtools.build.lib.events.EventKind.WARNING
        )
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun assertContainsEventWithFrequency(
        expectedMessage: String?, expectedFrequency: Int
    ): MutableList<com.google.devtools.build.lib.events.Event?> {
        return MoreAsserts.assertContainsEventWithFrequency(
            eventCollector, expectedMessage,
            expectedFrequency
        )
    }

    fun assertDoesNotContainEvent(unexpectedEvent: String?) {
        MoreAsserts.assertDoesNotContainEvent(eventCollector, unexpectedEvent)
    }

    fun assertContainsEventsInOrder(vararg expectedMessages: String?) {
        MoreAsserts.assertContainsEventsInOrder(eventCollector, expectedMessages)
    }

    companion object {
        /**
         * A handler that immediately throws [FailFastException] whenever an error or warning
         * occurs.
         * 
         * 
         * We do not reuse an existing unchecked exception type, because callers (e.g., test
         * assertions) need to be able to distinguish between organically occurring exceptions and
         * exceptions thrown by this handler.
         */
        private val FAIL_FAST_HANDLER: com.google.devtools.build.lib.events.EventHandler =
            object : com.google.devtools.build.lib.events.EventHandler {
                override fun handle(event: com.google.devtools.build.lib.events.Event) {
                    if (com.google.devtools.build.lib.events.EventKind.ERRORS_AND_WARNINGS.contains(event.getKind())) {
                        throw FailFastException(event.toString())
                    }
                }
            }
    }
}
