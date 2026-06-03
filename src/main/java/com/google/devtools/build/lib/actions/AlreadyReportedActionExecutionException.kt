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
package com.google.devtools.build.lib.actions

import com.google.devtools.build.lib.actions.ActionExecutionException

/**
 * This wrapper exception is used as a marker class to already reported errors. Errors are reported
 * at `AbstractBuilder.executeActionTask()` method in case of the builder not aborting in case
 * of exceptions (For example keepgoing).
 * 
 * 
 * Then In upper levels we wrap catch the exception and throw a BuildFailedException
 * unconditionally, that is caught and shown as error in AbstractBuildCommand (because the message
 * of the exception is !=null).
 * 
 * With this exception we detect that the error was already shown and we wrap it in a
 * BuildFailedException without message.
 */
class AlreadyReportedActionExecutionException(cause: ActionExecutionException) : ActionExecutionException(
    cause.getMessage(),
    cause.getCause(),
    cause.getAction(),
    cause.getRootCauses(),
    cause.isCatastrophe(),
    cause.getDetailedExitCode()
) {
    override fun showError(): Boolean {
        return false
    }
}
