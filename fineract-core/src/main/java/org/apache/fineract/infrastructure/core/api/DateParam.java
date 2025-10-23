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
package org.apache.fineract.infrastructure.core.api;

import java.time.LocalDate;
import java.util.Locale;
import org.apache.commons.lang3.StringUtils;
import org.apache.fineract.infrastructure.core.data.DateFormat;
import org.apache.fineract.infrastructure.core.exception.InvalidDateException;
import org.apache.fineract.infrastructure.core.serialization.JsonParserHelper;

/**
 * Class for parsing dates sent as query parameters
 */
public class DateParam {

    public static final String FROM_DATE_PARAM = "fromDate";
    public static final String TO_DATE_PARAM = "toDate";

    private final String dateAsString;

    public DateParam(final String dateStr) {
        this.dateAsString = dateStr;
    }

    public LocalDate getDate(String parameterName, DateFormat dateFormat, String localeAsString) {
        try {
            if (StringUtils.isBlank(dateAsString)) {
                throw new InvalidDateException("Date parameter '" + parameterName + "' is required but was not provided");
            }
            Locale locale = StringUtils.isBlank(localeAsString) ? Locale.getDefault() : JsonParserHelper.localeFromString(localeAsString);

            return JsonParserHelper.convertFrom(dateAsString, parameterName, dateFormat, locale);

        } catch (Exception e) {
            // Wrap other exceptions with better error message
            throw new InvalidDateException(parameterName + ": " + dateAsString, e);
        }
    }
}
