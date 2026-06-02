// Copyright 2015 The Bazel Authors. All Rights Reserved.
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
package com.google.testing.junit.junit4.runner

import org.junit.runner.Description
import org.junit.runner.Result
import org.junit.runner.notification.Failure
import org.junit.runner.notification.RunListener
import org.junit.runner.notification.RunNotifier
import org.junit.runner.notification.StoppedByUserException

/**
 * A [RunNotifier] that delegates all its operations to another `RunNotifier`.
 * This class is meant to be overridden to modify some behaviors.
 */
abstract class RunNotifierWrapper
/**
 * Creates a new instance.
 * 
 * @param delegate notifier to delegate to
 */(
    /**
     * @return the delegate
     */
    protected val delegate: RunNotifier
) : RunNotifier() {
    override fun addFirstListener(listener: RunListener?) {
        delegate.addFirstListener(listener)
    }

    override fun addListener(listener: RunListener?) {
        delegate.addListener(listener)
    }

    override fun removeListener(listener: RunListener?) {
        delegate.removeListener(listener)
    }

    override fun fireTestRunStarted(description: Description?) {
        delegate.fireTestRunStarted(description)
    }

    @Throws(StoppedByUserException::class)
    override fun fireTestStarted(description: Description?) {
        delegate.fireTestStarted(description)
    }

    override fun fireTestIgnored(description: Description?) {
        delegate.fireTestIgnored(description)
    }

    override fun fireTestAssumptionFailed(failure: Failure?) {
        delegate.fireTestAssumptionFailed(failure)
    }

    override fun fireTestFailure(failure: Failure?) {
        delegate.fireTestFailure(failure)
    }

    override fun fireTestFinished(description: Description?) {
        delegate.fireTestFinished(description)
    }

    override fun fireTestRunFinished(result: Result?) {
        delegate.fireTestRunFinished(result)
    }

    override fun pleaseStop() {
        delegate.pleaseStop()
    }
}
