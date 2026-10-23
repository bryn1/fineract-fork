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
package org.apache.fineract.accounting.rule.serialization;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.apache.fineract.infrastructure.core.exception.InvalidJsonException;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.exception.UnsupportedParameterException;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.junit.jupiter.api.Test;

/**
 * S1 characterization suite (MC 11150): boundary pins for
 * {@link AccountingRuleCommandFromApiJsonDeserializer} at pin 355287c8. (The happy-path tag round-trip
 * is already pinned by AccountRuleRequestSerializationTest at this pin; this file covers boundaries only.)
 */
class AccountingRuleCommandFromApiJsonDeserializerCharacterizationTest {

    private final AccountingRuleCommandFromApiJsonDeserializer deserializer = new AccountingRuleCommandFromApiJsonDeserializer(
            new FromJsonHelper());

    private static void assertErrorForParameter(PlatformApiDataValidationException ex, String parameterName) {
        assertTrue(ex.getErrors().stream().anyMatch(e -> parameterName.equals(e.getParameterName())),
                "Expected validation error for parameter '" + parameterName + "', got: " + ex.getErrors());
    }

    @Test
    void blankJsonThrowsInvalidJsonExceptionOnBothVerbs() {
        assertThrows(InvalidJsonException.class, () -> deserializer.validateForCreate(" "));
        assertThrows(InvalidJsonException.class, () -> deserializer.validateForUpdate(" "));
    }

    @Test
    void unsupportedParameterIsRejectedByName() {
        assertThrows(UnsupportedParameterException.class, () -> deserializer.validateForCreate("{\"bogus\":1}"));
    }

    @Test
    void createRequiresOfficeId() {
        String json = "{\"name\":\"R\",\"accountToCredit\":5,\"debitTags\":[1]}";
        assertErrorForParameter(assertThrows(PlatformApiDataValidationException.class, () -> deserializer.validateForCreate(json)),
                "officeId");
    }

    @Test
    void createRequiresNameAndRejectsNamesOver100Chars() {
        String blankName = "{\"officeId\":1,\"name\":\"\",\"accountToCredit\":5,\"debitTags\":[1]}";
        assertErrorForParameter(assertThrows(PlatformApiDataValidationException.class, () -> deserializer.validateForCreate(blankName)),
                "name");

        String longName = "{\"officeId\":1,\"name\":\"" + "n".repeat(101) + "\",\"accountToCredit\":5,\"debitTags\":[1]}";
        assertErrorForParameter(assertThrows(PlatformApiDataValidationException.class, () -> deserializer.validateForCreate(longName)),
                "name");
    }

    @Test
    void createRejectsEmptyTagArrayButAcceptsNullTagParameter() {
        String emptyArray = "{\"officeId\":1,\"name\":\"R\",\"accountToCredit\":5,\"creditTags\":[]}";
        assertErrorForParameter(assertThrows(PlatformApiDataValidationException.class, () -> deserializer.validateForCreate(emptyArray)),
                "creditTags");

        String nullTags = "{\"officeId\":1,\"name\":\"R\",\"accountToCredit\":5,\"accountToDebit\":6}";
        assertDoesNotThrow(() -> deserializer.validateForCreate(nullTags));
    }

    @Test
    void createRejectsNonNumericTagIdsAsAValidationErrorNotANumberFormatException() {
        // the builder converts the NumberFormatException into a "not.a.number" validation error and throws it
        String json = "{\"officeId\":1,\"name\":\"R\",\"accountToCredit\":5,\"debitTags\":[\"abc\"]}";
        assertThrows(PlatformApiDataValidationException.class, () -> deserializer.validateForCreate(json));
    }

    @Test
    void createAcceptsAccountAndTagsForTheSameSide() {
        // CHARACTERIZED (suspect — candidate for S2 decision): account-XOR-tags mutual exclusion is NOT a
        // validation rule on create — it is only enforced later by the write service, and asymmetrically
        // (see createWithBothDebitAccountAndDebitTagsIsRejectedByTheServiceNotTheValidator).
        String json = "{\"officeId\":1,\"name\":\"R\",\"accountToDebit\":6,\"debitTags\":[1],\"accountToCredit\":5}";
        assertDoesNotThrow(() -> deserializer.validateForCreate(json));
    }

    @Test
    void updateMakesOfficeIdAndNameOptional() {
        assertDoesNotThrow(() -> deserializer.validateForUpdate("{\"name\":\"Only name\"}"));
        assertDoesNotThrow(() -> deserializer.validateForUpdate("{\"officeId\":2}"));
    }

    @Test
    void updateSilentlyTreatsAnExplicitlyBlankNameAsNotSent() {
        // CHARACTERIZED (suspect — candidate for S2 decision): JsonParserHelper.extractStringNamed collapses
        // a blank JSON string to null ("name":"" extracts exactly like an omitted "name"), so an update that
        // explicitly clears/omits the name passes validation as a no-op instead of being rejected.
        assertDoesNotThrow(() -> deserializer.validateForUpdate("{\"name\":\"\"}"));
    }

    @Test
    void updateDoesNotEnforceTheAccountOrTagsPresenceThatCreateEnforces() {
        // CHARACTERIZED (suspect — candidate for S2 decision): create adds "accountToCredit,creditTags required"
        // / debit equivalents; validateForUpdate has NO such block, so an update can carry neither side.
        assertDoesNotThrow(() -> deserializer.validateForUpdate("{\"description\":\"just description\"}"));
    }
}
