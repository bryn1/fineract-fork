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
package org.apache.fineract.portfolio.charge.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * S4a characterization suite (MC 11183): pins the CURRENT value-mapping surface of the charge enums and
 * their JPA converters at pin 355287c8 — what IS, not what should be. Suspect pins are labeled; rows in
 * research/04 "S4 suspect map".
 */
class ChargeEnumFromIntCharacterizationTest {

    // ---- ChargeAppliesTo: null-safe, unknown collapses to INVALID ----

    @Test
    void chargeAppliesToFromIntTreatsNullAndUnknownAsInvalid() {
        assertSame(ChargeAppliesTo.INVALID, ChargeAppliesTo.fromInt(null));
        assertSame(ChargeAppliesTo.INVALID, ChargeAppliesTo.fromInt(99));
        assertSame(ChargeAppliesTo.LOAN, ChargeAppliesTo.fromInt(1));
        assertSame(ChargeAppliesTo.SAVINGS, ChargeAppliesTo.fromInt(2));
        assertSame(ChargeAppliesTo.CLIENT, ChargeAppliesTo.fromInt(3));
        assertSame(ChargeAppliesTo.SHARES, ChargeAppliesTo.fromInt(4));
        assertSame(ChargeAppliesTo.WORKING_CAPITAL_LOAN, ChargeAppliesTo.fromInt(5));
        // the INVALID member itself is never a valid wire value
        assertTrue(Arrays.stream(ChargeAppliesTo.validValues()).noneMatch(v -> Integer.valueOf(0).equals(v)));
    }

    // ---- ChargeCalculationType: null NPEs (asymmetric with the sibling) ----

    @Test
    void chargeCalculationTypeFromIntCollapsesUnknownToInvalid() {
        assertSame(ChargeCalculationType.INVALID, ChargeCalculationType.fromInt(99));
        assertSame(ChargeCalculationType.FLAT, ChargeCalculationType.fromInt(1));
        assertSame(ChargeCalculationType.PERCENT_OF_AMOUNT, ChargeCalculationType.fromInt(2));
        assertSame(ChargeCalculationType.PERCENT_OF_AMOUNT_AND_INTEREST, ChargeCalculationType.fromInt(3));
        assertSame(ChargeCalculationType.PERCENT_OF_INTEREST, ChargeCalculationType.fromInt(4));
        assertSame(ChargeCalculationType.PERCENT_OF_DISBURSEMENT_AMOUNT, ChargeCalculationType.fromInt(5));
    }

    @Test
    void chargeCalculationTypeFromIntThrowsNpeOnNullWhileSiblingsReturnInvalid() {
        // CHARACTERIZED (suspect — S4 suspect map S-C7): the switch expression does not guard null, so
        // fromInt(null) is a NullPointerException while ChargeAppliesTo/ChargeTimeType.fromInt(null)
        // collapse to INVALID — three sibling enums, two contracts.
        assertThrows(NullPointerException.class, () -> ChargeCalculationType.fromInt(null));
    }

    // ---- ChargePaymentMode: no INVALID member — every unknown silently becomes REGULAR ----

    @Test
    void chargePaymentModeFromIntSilentlyCollapsesEveryUnknownToRegular() {
        // CHARACTERIZED (suspect — S4 suspect map S-C8): there is no INVALID member; fromInt's default
        // swallows ALL non-1 values into REGULAR (and null NPEs, same asymmetry as S-C7).
        assertSame(ChargePaymentMode.REGULAR, ChargePaymentMode.fromInt(0));
        assertSame(ChargePaymentMode.ACCOUNT_TRANSFER, ChargePaymentMode.fromInt(1));
        assertSame(ChargePaymentMode.REGULAR, ChargePaymentMode.fromInt(99));
        assertThrows(NullPointerException.class, () -> ChargePaymentMode.fromInt(null));
    }

    // ---- JPA converters: null-safe wrappers over the same (suspect) fromInt contracts ----

    @Test
    void calculationTypeConverterMapsNullToNullAndDelegatesTheRestToFromInt() {
        ChargeCalculationTypeConverter converter = new ChargeCalculationTypeConverter();
        assertNull(converter.convertToDatabaseColumn(null));
        assertEquals(Integer.valueOf(2), converter.convertToDatabaseColumn(ChargeCalculationType.PERCENT_OF_AMOUNT));
        assertNull(converter.convertToEntityAttribute(null));
        assertSame(ChargeCalculationType.PERCENT_OF_AMOUNT, converter.convertToEntityAttribute(2));
        assertSame(ChargeCalculationType.INVALID, converter.convertToEntityAttribute(7));
    }

    @Test
    void paymentModeConverterMapsNullToNullAndCollapsesUnknownDbValuesToRegular() {
        // CHARACTERIZED (suspect — S4 suspect map S-C8, DB-side): a corrupted/out-of-range DB int reads
        // back as REGULAR — silently, not an error.
        ChargePaymentModeConverter converter = new ChargePaymentModeConverter();
        assertNull(converter.convertToDatabaseColumn(null));
        assertEquals(Integer.valueOf(1), converter.convertToDatabaseColumn(ChargePaymentMode.ACCOUNT_TRANSFER));
        assertNull(converter.convertToEntityAttribute(null));
        assertSame(ChargePaymentMode.REGULAR, converter.convertToEntityAttribute(7));
    }

    @Test
    void enumValueAndCodeLiteralsArePartOfTheWireContract() {
        // codes land in API JSON (EnumOptionData.code) — pinning the literals
        assertEquals("chargeAppliesTo.workingCapitalLoan", ChargeAppliesTo.WORKING_CAPITAL_LOAN.getCode());
        assertEquals("chargeCalculationType.percent.of.amount.and.interest", ChargeCalculationType.PERCENT_OF_AMOUNT_AND_INTEREST
                .getCode());
        assertEquals("chargepaymentmode.accounttransfer", ChargePaymentMode.ACCOUNT_TRANSFER.getCode());
        assertEquals(Integer.valueOf(5), ChargeAppliesTo.WORKING_CAPITAL_LOAN.getValue());
    }
}
