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
package org.apache.fineract.accounting.glaccount.serialization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.apache.fineract.accounting.glaccount.command.GLAccountCommand;
import org.apache.fineract.infrastructure.core.exception.InvalidJsonException;
import org.apache.fineract.infrastructure.core.exception.UnsupportedParameterException;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.junit.jupiter.api.Test;

/**
 * S1 characterization suite (MC 11150): pins JSON-boundary behavior of
 * {@link GLAccountCommandFromApiJsonDeserializer} at pin 355287c8.
 */
class GLAccountCommandFromApiJsonDeserializerCharacterizationTest {

    private final GLAccountCommandFromApiJsonDeserializer deserializer = new GLAccountCommandFromApiJsonDeserializer(
            new FromJsonHelper());

    @Test
    void blankJsonThrowsInvalidJsonException() {
        assertThrows(InvalidJsonException.class, () -> deserializer.commandFromApiJson("  "));
    }

    @Test
    void unsupportedParameterIsRejectedByName() {
        // checkForUnsupportedParameters rejects with UnsupportedParameterException — NOT a validation-error
        // payload; every unknown key aborts before any field validation runs.
        assertThrows(UnsupportedParameterException.class, () -> deserializer.commandFromApiJson("{\"bogusKey\":1}"));
    }

    @Test
    void fullPayloadMapsOntoTheCommandRecord() {
        String json = """
                {
                  "name": "Petty Cash",
                  "glCode": "1101",
                  "parentId": 3,
                  "disabled": true,
                  "manualEntriesAllowed": false,
                  "type": 1,
                  "usage": 2,
                  "description": "front desk cash",
                  "tagId": 7
                }
                """;

        GLAccountCommand command = deserializer.commandFromApiJson(json);

        assertEquals("Petty Cash", command.name());
        assertEquals("1101", command.glCode());
        assertEquals(3L, command.parentId());
        assertEquals(Boolean.TRUE, command.disabled());
        assertEquals(Boolean.FALSE, command.manualEntriesAllowed());
        assertEquals(1, command.type());
        assertEquals(2, command.usage());
        assertEquals("front desk cash", command.description());
        assertEquals(7L, command.tagId());
        assertNull(command.id());
    }

    @Test
    void absentOptionalFieldsBecomeNullNotDefaults() {
        GLAccountCommand command = deserializer.commandFromApiJson("{\"name\":\"n\",\"glCode\":\"g\",\"type\":1}");

        assertNull(command.parentId());
        assertNull(command.disabled());
        assertNull(command.usage());
        assertNull(command.tagId());
    }

    @Test
    void isHeaderAccountReflectsUsageTwo() {
        // usage==2 is HEADER per GLAccountUsage; anything else (including null) is not header at command level.
        GLAccountCommand header = deserializer.commandFromApiJson("{\"usage\":2}");
        GLAccountCommand detail = deserializer.commandFromApiJson("{\"usage\":1}");
        GLAccountCommand noUsage = deserializer.commandFromApiJson("{\"name\":\"n\"}");

        assertEquals(true, header.isHeaderAccount());
        assertEquals(false, detail.isHeaderAccount());
        assertEquals(false, noUsage.isHeaderAccount());
    }
}
