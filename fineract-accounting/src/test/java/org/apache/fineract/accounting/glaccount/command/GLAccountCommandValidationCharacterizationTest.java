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
package org.apache.fineract.accounting.glaccount.command;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.junit.jupiter.api.Test;

/**
 * S1 characterization suite (MC 11150): pins the CURRENT observable validation behavior of {@link GLAccountCommand}
 * at pin 355287c8 — what IS, not what should be. Nothing here is a fix request; suspect pins are labeled.
 */
class GLAccountCommandValidationCharacterizationTest {

    private static GLAccountCommand create(String name, String glCode, Integer type, Integer usage, Boolean manualEntriesAllowed,
            Long parentId, Long tagId) {
        return new GLAccountCommand(null, name, parentId, glCode, false, manualEntriesAllowed, type, usage, null, tagId);
    }

    private static void assertErrorForParameter(PlatformApiDataValidationException ex, String parameterName) {
        assertTrue(ex.getErrors().stream().anyMatch(e -> parameterName.equals(e.getParameterName())),
                "Expected validation error for parameter '" + parameterName + "', got: " + ex.getErrors());
    }

    @Test
    void createValidMinimalCommandPasses() {
        GLAccountCommand command = create("Cash", "1001", 1, 1, true, null, null);
        assertDoesNotThrow(command::validateForCreate);
    }

    @Test
    void createAcceptsNullUsageEvenThoughTheColumnIsNotNullable() {
        // CHARACTERIZED (suspect — candidate for S2 decision): usage has neither notNull() nor ignoreIfNull(),
        // so null falls silently through inMinMaxRange and create validates — while acc_gl_account.account_usage
        // is nullable=false (GLAccount entity). The guard is missing at this layer.
        GLAccountCommand command = create("Cash", "1001", 1, null, true, null, null);
        assertDoesNotThrow(command::validateForCreate);
    }

    @Test
    void createRejectsBlankName() {
        GLAccountCommand command = create("", "1001", 1, 1, true, null, null);
        assertErrorForParameter(assertThrows(PlatformApiDataValidationException.class, command::validateForCreate), "name");
    }

    @Test
    void createRejectsNameLongerThan200() {
        GLAccountCommand command = create("n".repeat(201), "1001", 1, 1, true, null, null);
        assertErrorForParameter(assertThrows(PlatformApiDataValidationException.class, command::validateForCreate), "name");
    }

    @Test
    void createAcceptsNameOfExactly200() {
        GLAccountCommand command = create("n".repeat(200), "1001", 1, 1, true, null, null);
        assertDoesNotThrow(command::validateForCreate);
    }

    @Test
    void createRejectsGlCodeLongerThan45() {
        GLAccountCommand command = create("Cash", "1".repeat(46), 1, 1, true, null, null);
        assertErrorForParameter(assertThrows(PlatformApiDataValidationException.class, command::validateForCreate), "glCode");
    }

    @Test
    void createRejectsTypeOutsideOneToFive() {
        GLAccountCommand tooHigh = create("Cash", "1001", 6, 1, true, null, null);
        assertErrorForParameter(assertThrows(PlatformApiDataValidationException.class, tooHigh::validateForCreate), "type");

        GLAccountCommand tooLow = create("Cash", "1001", 0, 1, true, null, null);
        assertErrorForParameter(assertThrows(PlatformApiDataValidationException.class, tooLow::validateForCreate), "type");
    }

    @Test
    void createRejectsNullType() {
        GLAccountCommand command = create("Cash", "1001", null, 1, true, null, null);
        assertErrorForParameter(assertThrows(PlatformApiDataValidationException.class, command::validateForCreate), "type");
    }

    @Test
    void createRejectsUsageOutsideOneToTwo() {
        GLAccountCommand command = create("Cash", "1001", 1, 3, true, null, null);
        assertErrorForParameter(assertThrows(PlatformApiDataValidationException.class, command::validateForCreate), "usage");
    }

    @Test
    void createRequiresManualEntriesAllowedNonNull() {
        // notBlank() on a Boolean: null (missing) is reported mandatory, false is accepted ("false" is not blank).
        GLAccountCommand missing = create("Cash", "1001", 1, 1, null, null, null);
        assertErrorForParameter(assertThrows(PlatformApiDataValidationException.class, missing::validateForCreate),
                "manualEntriesAllowed");

        GLAccountCommand falseValue = create("Cash", "1001", 1, 1, false, null, null);
        assertDoesNotThrow(falseValue::validateForCreate);
    }

    @Test
    void createRejectsNonPositiveParentIdAndTagId() {
        GLAccountCommand badParent = create("Cash", "1001", 1, 1, true, 0L, null);
        assertErrorForParameter(assertThrows(PlatformApiDataValidationException.class, badParent::validateForCreate), "parentId");

        GLAccountCommand badTag = create("Cash", "1001", 1, 1, true, null, -1L);
        assertErrorForParameter(assertThrows(PlatformApiDataValidationException.class, badTag::validateForCreate), "tagId");
    }

    @Test
    void updateWithNoParametersAtAllIsRejectedUnderParameterId() {
        GLAccountCommand empty = new GLAccountCommand(null, null, null, null, null, null, null, null, null, null);
        PlatformApiDataValidationException ex = assertThrows(PlatformApiDataValidationException.class, empty::validateForUpdate);
        // anyOfNotNull() reports the aggregated error under the parameter name "id".
        assertErrorForParameter(ex, "id");
    }

    @Test
    void updateAllowsPartialFields() {
        GLAccountCommand nameOnly = new GLAccountCommand(null, "New Name", null, null, null, null, null, null, null, null);
        assertDoesNotThrow(nameOnly::validateForUpdate);
    }

    @Test
    void updateRejectsExplicitBlankNameAndBlankDescription() {
        GLAccountCommand blankName = new GLAccountCommand(null, "", null, null, null, null, null, null, null, null);
        assertErrorForParameter(assertThrows(PlatformApiDataValidationException.class, blankName::validateForUpdate), "name");

        GLAccountCommand blankDescription = new GLAccountCommand(null, "Name", null, null, null, null, null, null, "", null);
        assertErrorForParameter(assertThrows(PlatformApiDataValidationException.class, blankDescription::validateForUpdate),
                "description");
    }

    @Test
    void updateRejectsNonPositiveTagIdLikeCreateDoes() {
        // S2 FIX (MC 11153, suspect 6.1): the TAGID check now runs BEFORE the errors-list throw, so an update
        // carrying tagId=0 or a negative tagId is rejected as a validation error — the same live rule create
        // already enforced. (Previously the check trailed the throw and was dead on the path.)
        GLAccountCommand zeroTag = new GLAccountCommand(null, "Name", null, "1001", null, null, null, null, null, 0L);
        assertErrorForParameter(assertThrows(PlatformApiDataValidationException.class, zeroTag::validateForUpdate), "tagId");

        GLAccountCommand negativeTag = new GLAccountCommand(null, "Name", null, "1001", null, null, null, null, null, -1L);
        assertErrorForParameter(assertThrows(PlatformApiDataValidationException.class, negativeTag::validateForUpdate), "tagId");
    }
}
