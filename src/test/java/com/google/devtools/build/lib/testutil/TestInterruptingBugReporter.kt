// Copyright 2022 The Bazel Authors. All rights reserved.
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

import com.google.common.flogger.GoogleLogger
import com.google.devtools.build.lib.bugreport.BugReporter
import com.google.devtools.build.lib.bugreport.Crash
import com.google.devtools.build.lib.bugreport.CrashContext
import java.util.concurrent.atomic.AtomicReference

/**
 * [TestRule] that interrupts the main test thread when an unexpected exception in an async
 * thread is encountered.
 * 
 * 
 * Designed for use in tests that would otherwise hang indefinitely in the event of a bug. In
 * blaze, unexpected exceptions are typically handled by calling [Runtime.halt] to terminate
 * the JVM. In Java tests, however, this does not work because:
 * 
 * 
 *  * If [com.google.devtools.build.lib.runtime.BlazeRuntime] is not in scope for the test,
 * there may not be a relevant [UncaughtExceptionHandler] installed.
 *  * Calling [Runtime.halt] in a Java test is not allowed and leads to a [       ].
 * 
 * 
 * 
 * Consider a class with the following method:
 * 
 * <pre>`public Future<Void> doSomethingAsync() {   SettableFuture<Void> future = SettableFuture.create();   executor.execute(() -> {     ...     Preconditions.checkState(someCondition);     future.set(null);   });   return future; } `</pre>
 * 
 * and a corresponding unit test:
 * 
 * <pre>`publicvoid testSomethingAsync() throws Exception {   Future<Void> future = underTest.doSomethingAsync();   future.get(); } `</pre>
 * 
 * If the call to `Preconditions.checkState` fails, the test hangs indefinitely. Diagnosing
 * the issue would require waiting for the test to time out and then analyzing the test log to find
 * the uncaught exception. Instead, using `TestInterruptingBugReporter` will immediately
 * interrupt the main test thread and display the uncaught exception as the test's failure cause.
 * 
 * 
 * `TestInteruptingBugReporter` can also be used as a [BugReporter] if the system
 * under test is designed to accept one:
 * 
 * <pre>`public Future<Void> doSomethingAsync(BugReporter bugReporter) {   SettableFuture<Void> future = SettableFuture.create();   executor.execute(() -> {     ...     if (!someCondition) {       bugReporter.sendBugReport(new IllegalStateException("someCondition was false");     }     future.set(null);   });   return future; } `</pre>
 * 
 * Example usage:
 * 
 * <pre>`publicfinal TestInterruptingBugReporter bugReporter = new TestInterruptingBugReporter();  publicvoid testSomethingAsync() throws Exception {   Future<Void> future = underTest.doSomethingAsync(bugReporter);   future.get(); } `</pre>
 */
class TestInterruptingBugReporter

    : BugReporter, java.lang.Thread.UncaughtExceptionHandler, org.junit.rules.TestRule {
    // This is the main test thread so long as this class is being used as documented (instantiated
    // as a field in the test class).
    private val testThread: java.lang.Thread = java.lang.Thread.currentThread()

    private val bug: AtomicReference<Throwable?> = AtomicReference<Throwable?>()

    override fun apply(
        base: org.junit.runners.model.Statement,
        description: org.junit.runner.Description?
    ): org.junit.runners.model.Statement {
        return object : org.junit.runners.model.Statement() {
            @Throws(Throwable::class)
            override fun evaluate() {
                val originalHandler: java.lang.Thread.UncaughtExceptionHandler? =
                    java.lang.Thread.getDefaultUncaughtExceptionHandler()
                java.lang.Thread.setDefaultUncaughtExceptionHandler(this@TestInterruptingBugReporter)
                try {
                    base.evaluate()
                } catch (e: java.lang.InterruptedException) {
                    throw com.google.common.base.MoreObjects.firstNonNull<Throwable?>(bug.get(), e)
                } finally {
                    java.lang.Thread.setDefaultUncaughtExceptionHandler(originalHandler)
                }
            }
        }
    }

    override fun sendBugReport(exception: Throwable, args: MutableList<String?>?, vararg values: String?) {
        handle(exception, "call to sendBugReport", java.lang.Thread.currentThread())
    }

    override fun sendNonFatalBugReport(exception: Throwable) {
        handle(exception, "call to sendNonFatalBugReport", java.lang.Thread.currentThread())
    }

    override fun handleCrash(crash: Crash, ctx: CrashContext?) {
        handle(crash.throwable, "call to handleCrash", java.lang.Thread.currentThread())
    }

    override fun uncaughtException(thread: java.lang.Thread, exception: Throwable) {
        handle(exception, "uncaught exception", thread)
    }

    @kotlin.jvm.Synchronized
    private fun handle(exception: Throwable, context: String?, thread: java.lang.Thread) {
        if (thread == testThread) {
            throw java.lang.IllegalStateException(exception)
        }
        if (bug.compareAndSet(null, exception)) {
            logger.atSevere().withCause(exception).log(
                "Handling %s in thread %s by interrupting the main test thread",
                context, thread.getName()
            )
            testThread.interrupt()
        } else {
            logger.atSevere().withCause(exception).log(
                "Ignoring %s in thread %s since a previous exception was seen",
                context, thread.getName()
            )
            bug.get().addSuppressed(exception)
        }
    }

    companion object {
        private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()
    }
}
