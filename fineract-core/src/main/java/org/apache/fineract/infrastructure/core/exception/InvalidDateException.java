/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.fineract.infrastructure.core.exception;

/**
 * A {@link RuntimeException} thrown when date parsing fails or date format is invalid.
 */
public class InvalidDateException extends AbstractPlatformDomainRuleException {

    public InvalidDateException(String parameterName, String dateValue, String expectedFormat) {
        super("error.msg.invalid.date.format",
                "The date value '" + dateValue + "' for parameter '" + parameterName + "' is invalid. Expected format: " + expectedFormat,
                parameterName, dateValue, expectedFormat);
    }

    public InvalidDateException(String message) {
        super("error.msg.invalid.date.format", message);
    }

    public InvalidDateException(String message, Throwable cause) {
        super("error.msg.invalid.date.format", message + (cause != null ? ": " + cause.getMessage() : ""));
    }
}
