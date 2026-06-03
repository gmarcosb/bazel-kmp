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
package com.google.devtools.build.lib.testutil

import com.google.devtools.build.lib.events.Event
import com.google.devtools.build.lib.testutil.MoreAsserts.assertContainsEvent

/** A helper class for tests providing a simple interface for asserts.  */
object MoreAsserts {
    /**
     * Scans if an instance of given class is strongly reachable from a given object.
     * 
     * 
     * Runs breadth-first search in object reachability graph to check if an instance of `clz
    ` *  can be reached. **Note:** This method can take a long time if analyzed
     * data structure spans across large part of heap and may need a lot of memory.
     * 
     * @param start object to start the search from
     * @param clazz class to look for
     */
    fun assertInstanceOfNotReachable(start: Any?, clazz: java.lang.Class<*>) {
        val p: com.google.common.base.Predicate<Any?> =
            com.google.common.base.Predicate { obj: Any? -> clazz.isAssignableFrom(obj.javaClass) }
        if (isRetained(p, start)) {
            org.junit.Assert.fail("Found an instance of " + clazz.getCanonicalName() + " reachable from " + start)
        }
    }

    private val NON_STRONG_REF: java.lang.reflect.Field?

    init {
        try {
            NON_STRONG_REF = java.lang.ref.Reference::class.java.getDeclaredField("referent")
        } catch (e: java.lang.SecurityException) {
            throw java.lang.RuntimeException(e)
        } catch (e: java.lang.NoSuchFieldException) {
            throw java.lang.RuntimeException(e)
        }
    }

    val ALL_STRONG_REFS: com.google.common.base.Predicate<java.lang.reflect.Field?> =
        com.google.common.base.Predicates.equalTo<java.lang.reflect.Field?>(NON_STRONG_REF)

    private fun isRetained(predicate: com.google.common.base.Predicate<Any?>, start: Any?): Boolean {
        val visited: IdentityHashMap<Any?, Any?> = IdentityHashMap<Any?, Any?>()
        visited.put(start, start)
        val toScan: java.util.Queue<Any> = ArrayDeque<Any>()
        toScan.add(start)

        while (!toScan.isEmpty()) {
            val current: Any = toScan.poll()
            if (current.javaClass.isArray()) {
                if (current.javaClass.getComponentType().isPrimitive()) {
                    continue
                }

                for (ref in current as Array<Any?>) {
                    if (ref != null) {
                        if (predicate.apply(ref)) {
                            return true
                        }
                        if (visited.put(ref, ref) == null) {
                            toScan.add(ref)
                        }
                    }
                }
            } else {
                // iterate *all* fields (getFields() returns only accessible ones)
                var clazz: java.lang.Class<*>? = current.javaClass
                while (clazz != null) {
                    for (f in clazz.getDeclaredFields()) {
                        if (f.getType().isPrimitive() || ALL_STRONG_REFS.apply(f)) {
                            continue
                        }

                        try {
                            f.setAccessible(true)
                        } catch (e: java.lang.RuntimeException) {
                            // JDK9 can throw InaccessibleObjectException when internal modules are accessed.
                            // This isn't available in JDK8, so catch RuntimeException
                            // We can use a JVM arg --add_opens to suppress that, but that involves every
                            // test adding every JVM module to the target.
                            continue
                        }
                        try {
                            val ref: Any? = f.get(current)
                            if (ref != null) {
                                if (predicate.apply(ref)) {
                                    return true
                                }
                                if (visited.put(ref, ref) == null) {
                                    toScan.add(ref)
                                }
                            }
                        } catch (e: java.lang.IllegalArgumentException) {
                            throw java.lang.IllegalStateException("Error when scanning the heap", e)
                        } catch (e: java.lang.IllegalAccessException) {
                            throw java.lang.IllegalStateException("Error when scanning the heap", e)
                        }
                    }
                    clazz = clazz.getSuperclass()
                }
            }
        }
        return false
    }

    fun assertEqualsUnifyingLineEnds(expected: String?, actual: String?) {
        var actual = actual
        if (actual != null) {
            actual = actual.replace(java.lang.System.getProperty("line.separator").toRegex(), "\n")
        }
        Truth.assertThat(actual).isEqualTo(expected)
    }

    fun assertContainsWordsWithQuotes(message: String, vararg strings: String?) {
        for (string in strings) {
            Truth.assertWithMessage("%s should contain '%s' (with quotes)", message, string)
                .that(message.contains("'" + string + "'"))
                .isTrue()
        }
    }

    fun assertNonZeroExitCode(exitCode: Int, stdout: String?, stderr: String?) {
        if (exitCode == 0) {
            org.junit.Assert.fail(
                ("expected non-zero exit code but exit code was 0 and stdout was <"
                        + stdout
                        + "> and stderr was <"
                        + stderr
                        + ">")
            )
        }
    }

    fun assertZeroExitCode(exitCode: Int, stdout: String?, stderr: String?) {
        assertExitCode(0, exitCode, stdout, stderr)
    }

    fun assertZeroExitCode(exitCode: Int, recordingOutErr: RecordingOutErr) {
        assertExitCode(0, exitCode, recordingOutErr.outAsLatin1(), recordingOutErr.errAsLatin1())
    }

    fun assertExitCode(
        expectedExitCode: Int, exitCode: Int, stdout: String?, stderr: String?
    ) {
        if (exitCode != expectedExitCode) {
            org.junit.Assert.fail(
                String.format(
                    "expected exit code <%d> but exit code was <%d> and stdout was <%s> "
                            + "and stderr was <%s>",
                    expectedExitCode, exitCode, stdout, stderr
                )
            )
        }
    }

    fun assertExitCode(
        expectedExitCode: Int, exitCode: Int, recordingOutErr: RecordingOutErr
    ) {
        assertExitCode(
            expectedExitCode, exitCode, recordingOutErr.outAsLatin1(), recordingOutErr.errAsLatin1()
        )
    }

    fun assertEqualWithStdoutAndErr(
        expected: Any, actual: Any?, stdout: String?, stderr: String?
    ) {
        if (expected != actual) {
            org.junit.Assert.fail(
                String.format(
                    "expected <%s> but was <%s> and stdout was <%s> and stderr was <%s>",
                    expected, actual, stdout, stderr
                )
            )
        }
    }

    fun assertStderrContainsString(expected: String?, stdout: String?, stderr: String) {
        if (!stderr.contains(expected)) {
            org.junit.Assert.fail(
                ("expected stderr to contain string <"
                        + expected
                        + "> but stdout was <"
                        + stdout
                        + "> and stderr was <"
                        + stderr
                        + ">")
            )
        }
    }

    @kotlin.jvm.JvmStatic
    fun assertStdoutContainsRegex(expectedRegex: String?, stdout: String?, stderr: String?) {
        if (!java.util.regex.Pattern.compile(expectedRegex).matcher(stdout).find()) {
            org.junit.Assert.fail(
                ("expected stdout to contain regex <"
                        + expectedRegex
                        + "> but stdout was <"
                        + stdout
                        + "> and stderr was <"
                        + stderr
                        + ">")
            )
        }
    }

    fun assertStderrContainsRegex(expectedRegex: String?, stdout: String?, stderr: String?) {
        if (!java.util.regex.Pattern.compile(expectedRegex).matcher(stderr).find()) {
            org.junit.Assert.fail(
                ("expected stderr to contain regex <"
                        + expectedRegex
                        + "> but stdout was <"
                        + stdout
                        + "> and stderr was <"
                        + stderr
                        + ">")
            )
        }
    }

    /**
     * If the specified EventCollector contains any events, an informative assertion fails in the
     * context of the specified TestCase.
     */
    fun assertNoEvents(eventCollector: Iterable<Event>) {
        val eventsString = eventsToString(eventCollector)
        Truth.assertThat(eventsString).isEmpty()
    }

    /**
     * If the specified EventCollector contains an unexpected number of events, an informative
     * assertion fails in the context of the specified TestCase.
     */
    fun assertEventCount(expectedCount: Int, eventCollector: EventCollector) {
        Truth.assertWithMessage(eventsToString(eventCollector))
            .that(eventCollector.count())
            .isEqualTo(expectedCount)
    }

    /**
     * If the specified EventCollector contains an unexpected number of events, an informative
     * assertion fails in the context of the specified TestCase.
     */
    fun assertEventCountAtLeast(minCount: Int, eventCollector: EventCollector) {
        Truth.assertWithMessage(eventsToString(eventCollector))
            .that(eventCollector.count())
            .isAtLeast(minCount)
    }

    /**
     * If the specified EventCollector does not contain an event which has 'expectedEvent' as a
     * substring, an informative assertion fails. Otherwise the matching event is returned.
     */
    fun assertContainsEvent(eventCollector: Iterable<Event?>?, expectedEvent: String?): Event? {
        return assertContainsEvent(eventCollector, expectedEvent, EventKind.ALL_EVENTS)
    }

    /**
     * If the specified EventCollector does not contain an event which has 'expectedEvent' as a
     * substring, an informative assertion fails. Otherwise the matching event is returned.
     */
    fun assertContainsEvent(
        eventCollector: Iterable<Event>, expectedEvent: String, kind: EventKind
    ): Event? {
        return MoreAsserts.assertContainsEvent(
            eventCollector,
            expectedEvent,
            com.google.common.collect.ImmutableSet.of<EventKind?>(kind)
        )
    }

    /**
     * If the specified EventCollector does not contain an event of a kind of 'kinds' which has
     * 'expectedEvent' as a substring, an informative assertion fails. Otherwise the matching event is
     * returned.
     */
    fun assertContainsEvent(
        eventCollector: Iterable<Event>, expectedEvent: String, kinds: MutableSet<EventKind?>
    ): Event? {
        for (event in eventCollector) {
            // We want to be able to check for the location and the message type (error / warning).
            // Consequently, we use toString() instead of getMessage().
            if (event.toString().contains(expectedEvent) && kinds.contains(event.getKind())) {
                return event
            }
        }
        val eventsString = eventsToString(eventCollector)
        Truth.assertWithMessage(
            "Event '%s' not found%s",
            expectedEvent,
            (if (eventsString.length == 0) "" else ("; found these though:" + eventsString))
        )
            .that(false)
            .isTrue()
        return null // unreachable
    }

    /**
     * If `eventCollector` does not contain an event which matches `expectedEventPattern`,
     * fails with an informative assertion.
     */
    fun assertContainsEvent(
        eventCollector: Iterable<Event>, expectedEventPattern: java.util.regex.Pattern, vararg kinds: EventKind?
    ): Event? {
        return MoreAsserts.assertContainsEvent(
            eventCollector,
            expectedEventPattern,
            com.google.common.collect.ImmutableSet.copyOf<EventKind?>(kinds)
        )
    }

    /**
     * If `eventCollector` does not contain an event which matches `expectedEventPattern`,
     * fails with an informative assertion.
     */
    fun assertContainsEvent(
        eventCollector: Iterable<Event>, expectedEventPattern: java.util.regex.Pattern, kinds: MutableSet<EventKind?>
    ): Event? {
        for (event in eventCollector) {
            // Does the event message match the expected regex?
            if (!expectedEventPattern.matcher(event.toString()).find()) {
                continue
            }
            // Was an expected kind given, and does the event match?
            if (!kinds.isEmpty() && !kinds.contains(event.getKind())) {
                continue
            }
            // Return the event, assertion successful
            return event
        }
        val eventsString = eventsToString(eventCollector)
        var failureMessage = "Event matching '" + expectedEventPattern + "' not found"
        if (!eventsString.isEmpty()) {
            failureMessage += "; found these though: " + eventsString
        }
        org.junit.Assert.fail(failureMessage)
        return null // unreachable
    }

    fun assertNotContainsEvent(
        eventCollector: Iterable<Event>, unexpectedEventPattern: java.util.regex.Pattern?
    ) {
        for (event in eventCollector) {
            assertThat(event.toString()).doesNotMatch(unexpectedEventPattern)
        }
    }

    /**
     * If the specified EventCollector contains an event which has 'unexpectedEvent' as a substring,
     * an informative assertion fails.
     */
    fun assertDoesNotContainEvent(
        eventCollector: Iterable<Event>, unexpectedEvent: String
    ) {
        assertDoesNotContainEvents(eventCollector, unexpectedEvent)
    }

    /**
     * If the specified EventCollector contains an event which has any of 'unexpectedEvents' as a
     * substring, an informative assertion fails.
     */
    fun assertDoesNotContainEvents(
        eventCollector: Iterable<Event>, vararg unexpectedEvents: String
    ) {
        for (event in eventCollector) {
            for (unexpectedEvent in unexpectedEvents) {
                Truth.assertWithMessage(
                    "Unexpected string '%s' matched following event:\n%s",
                    unexpectedEvent, event.getMessage()
                )
                    .that(event.getMessage())
                    .doesNotContain(unexpectedEvent)
            }
        }
    }

    /**
     * Returns a string consisting of each event in the specified collector, preceded by a newline.
     */
    private fun eventsToString(eventCollector: Iterable<Event>): String {
        val buf: java.lang.StringBuilder = java.lang.StringBuilder()
        eventLoop@ for (event in eventCollector) {
            for (ignoredPrefix in TestConstants.IGNORED_MESSAGE_PREFIXES) {
                if (event.getMessage().startsWith(ignoredPrefix)) {
                    continue@eventLoop
                }
            }
            buf.append('\n').append(event)
        }
        return buf.toString()
    }

    /**
     * If "expectedSublist" is not a sublist of "arguments", an informative assertion is failed in the
     * context of the specified TestCase.
     * 
     * 
     * Argument order mnemonic: assert(X)ContainsSublist(Y).
     */
    fun <T> assertContainsSublist(arguments: MutableList<T?>, vararg expectedSublist: T?) {
        val sublist: MutableList<T?> = java.util.Arrays.asList<T?>(*expectedSublist)
        try {
            Truth.assertThat(Collections.indexOfSubList(arguments, sublist)).isNotEqualTo(-1)
        } catch (e: java.lang.AssertionError) {
            throw java.lang.AssertionError("Did not find " + sublist + " as a sublist of " + arguments, e)
        }
    }

    /**
     * If "expectedSublist" is a sublist of "arguments", an informative assertion is failed in the
     * context of the specified TestCase.
     * 
     * 
     * Argument order mnemonic: assert(X)DoesNotContainSublist(Y).
     */
    fun <T> assertDoesNotContainSublist(arguments: MutableList<T?>, vararg expectedSublist: T?) {
        val sublist: MutableList<T?> = java.util.Arrays.asList<T?>(*expectedSublist)
        try {
            Truth.assertThat(Collections.indexOfSubList(arguments, sublist)).isEqualTo(-1)
        } catch (e: java.lang.AssertionError) {
            throw java.lang.AssertionError("Found " + sublist + " as a sublist of " + arguments, e)
        }
    }

    /**
     * Check to see if each element of expectedMessages is the beginning of a message in
     * eventCollector, in order, as in [.containsSublistWithGapsAndEqualityChecker]. If not, an
     * informative assertion is failed
     */
    fun assertContainsEventsInOrder(
        eventCollector: Iterable<Event>, vararg expectedMessages: String?
    ) {
        val failure =
            MoreAsserts.containsSublistWithGapsAndEqualityChecker<Event?, String?>(
                com.google.common.collect.ImmutableList.copyOf<Event?>(eventCollector),
                com.google.common.base.Function { pair: Pair<S?, T?>? ->
                    pair.first.getMessage().contains(pair.second)
                },
                *expectedMessages
            )

        val eventsString = eventsToString(eventCollector)
        Truth.assertWithMessage(
            "Event '%s' not found in proper order%s",
            failure, (if (eventsString.length == 0) "" else ("; found these though:" + eventsString))
        )
            .that(failure)
            .isNull()
    }

    /**
     * Check to see if each element of expectedSublist is in arguments, according to the
     * equalityChecker, in the same order as in expectedSublist (although with other interspersed
     * elements in arguments allowed).
     * 
     * @param equalityChecker function that takes a `Pair<S, T>` element and returns true if the
     * elements of the pair are equal by its lights.
     * @return first element not in arguments in order, or null if success.
     */
    internal fun <S, T> containsSublistWithGapsAndEqualityChecker(
        arguments: MutableList<S?>,
        equalityChecker: com.google.common.base.Function<Pair<S?, T?>?, Boolean?>,
        vararg expectedSublist: T?
    ): T? {
        val iter = arguments.iterator()
        outerLoop@ for (expected in expectedSublist) {
            while (iter.hasNext()) {
                val actual = iter.next()
                if (equalityChecker.apply(Pair.of(actual, expected))) {
                    continue@outerLoop
                }
            }
            return expected
        }
        return null
    }

    fun assertContainsEventWithFrequency(
        events: Iterable<Event>, expectedMessage: String?, expectedFrequency: Int
    ): MutableList<Event?> {
        val builder: com.google.common.collect.ImmutableList.Builder<Event?> =
            com.google.common.collect.ImmutableList.builder<Event?>()
        for (event in events) {
            if (event.getMessage().contains(expectedMessage)) {
                builder.add(event)
            }
        }
        val foundEvents: MutableList<Event?> = builder.build()
        Truth.assertWithMessage(events.toString()).that(foundEvents).hasSize(expectedFrequency)
        return foundEvents
    }
}
