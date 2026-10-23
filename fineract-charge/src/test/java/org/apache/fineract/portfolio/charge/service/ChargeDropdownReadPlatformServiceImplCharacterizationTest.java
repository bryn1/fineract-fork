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
package org.apache.fineract.portfolio.charge.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.apache.fineract.infrastructure.core.data.EnumOptionData;
import org.apache.fineract.portfolio.charge.domain.ChargeAppliesTo;
import org.apache.fineract.portfolio.charge.domain.ChargeTimeType;
import org.junit.jupiter.api.Test;

/**
 * S4a characterization suite (MC 11183): pins the CURRENT dropdown option SETS and their EXACT ORDER of
 * {@link ChargeDropdownReadPlatformServiceImpl} at pin 355287c8 — these lists are the public
 * ChargeDropdownReadPlatformService DTO surface (they shape API JSON for every client UI). The class is
 * pure enum arithmetic (zero DB access), so every method is pinnable without a database. Suspect pins are
 * labeled; rows in research/04 "S4 suspect map".
 */
class ChargeDropdownReadPlatformServiceImplCharacterizationTest {

    private final ChargeDropdownReadPlatformServiceImpl service = new ChargeDropdownReadPlatformServiceImpl();

    private static List<Long> ids(List<EnumOptionData> options) {
        return options.stream().map(EnumOptionData::getId).toList();
    }

    @Test
    void retrieveCalculationTypesOffersFiveTypesWithoutInvalid() {
        assertEquals(List.of(1L, 2L, 3L, 4L, 5L), ids(service.retrieveCalculationTypes()));
    }

    @Test
    void retrieveApplicableToTypesSkipsInvalidInEnumOrder() {
        assertEquals(List.of(1L, 2L, 3L, 4L, 5L), ids(service.retrieveApplicableToTypes()));
    }

    @Test
    void retrieveCollectionTimeTypesExcludesInvalidAndSavingsClosure() {
        List<Long> ids = ids(service.retrieveCollectionTimeTypes());
        // SAVINGS_CLOSURE (4) is excluded by name in the loop, INVALID (0) likewise
        assertFalse(ids.contains(0L));
        assertFalse(ids.contains(4L));
        assertEquals(List.of(1L, 2L, 3L, 5L, 6L, 7L, 8L, 9L, 10L, 11L, 12L, 13L, 14L, 15L, 16L), ids);
    }

    @Test
    void savingsDropdownHidesSavingsClosureThatCreateValidationAccepts() {
        // CHARACTERIZED (suspect — S4 suspect map S-C9): the savings collection-time dropdown omits
        // SAVINGS_CLOSURE(4) (the option is commented out) while ChargeTimeType.validSavingsValues() —
        // used by the deserializer's savings branch — still ACCEPTS it: the API accepts a value no
        // dropdown offers, and the commented-out line is the only trace of the intent.
        assertFalse(ids(service.retrieveSavingsCollectionTimeTypes()).contains(4L));
        assertTrue(List.of(ChargeTimeType.validSavingsValues()).contains(4));
    }

    @Test
    void retrievePaymentModesOffersBothModesRegularFirst() {
        assertEquals(List.of(0L, 1L), ids(service.retrievePaymentModes()));
    }

    @Test
    void perProductListsAreHardcodedSubsetsNotDerivedFromValidValues() {
        // exact order and membership are the observable contract (UI dropdown order follows this list)
        assertEquals(List.of(1L, 2L, 3L, 4L, 5L), ids(service.retrieveLoanCalculationTypes()));
        assertEquals(List.of(1L, 2L, 8L, 9L, 12L), ids(service.retrieveLoanCollectionTimeTypes()));
        assertEquals(List.of(1L, 2L), ids(service.retrieveSavingsCalculationTypes()));
        assertEquals(List.of(2L, 3L, 5L, 6L, 7L, 11L, 10L, 16L), ids(service.retrieveSavingsCollectionTimeTypes()));
        assertEquals(List.of(1L), ids(service.retrieveClientCalculationTypes()));
        assertEquals(List.of(2L), ids(service.retrieveClientCollectionTimeTypes()));
        assertEquals(List.of(1L, 2L), ids(service.retrieveSharesCalculationTypes()));
        assertEquals(List.of(13L, 14L, 15L), ids(service.retrieveSharesCollectionTimeTypes()));
    }

    @Test
    void workingCapitalOverloadsNarrowOnlyForWorkingCapitalLoan() {
        // calculation types for WCL: no time given = union over the WCL product times (FLAT + %amount)
        assertEquals(List.of(1L, 2L), ids(service.retrieveCalculationTypes(ChargeAppliesTo.WORKING_CAPITAL_LOAN, null)));
        // DISBURSEMENT: FLAT + PERCENT_OF_AMOUNT; SPECIFIED_DUE_DATE: FLAT only
        assertEquals(List.of(1L, 2L),
                ids(service.retrieveCalculationTypes(ChargeAppliesTo.WORKING_CAPITAL_LOAN, ChargeTimeType.DISBURSEMENT)));
        assertEquals(List.of(1L),
                ids(service.retrieveCalculationTypes(ChargeAppliesTo.WORKING_CAPITAL_LOAN, ChargeTimeType.SPECIFIED_DUE_DATE)));
        // a time type outside the WCL set yields the EMPTY list (silent, no error)
        assertEquals(List.of(), ids(service.retrieveCalculationTypes(ChargeAppliesTo.WORKING_CAPITAL_LOAN, ChargeTimeType.MONTHLY_FEE)));
        // collection times + payment modes for WCL
        assertEquals(List.of(1L, 2L), ids(service.retrieveCollectionTimeTypes(ChargeAppliesTo.WORKING_CAPITAL_LOAN)));
        assertEquals(List.of(0L), ids(service.retrievePaymentModes(ChargeAppliesTo.WORKING_CAPITAL_LOAN)));
    }

    @Test
    void nonWorkingCapitalOverloadsDelegateToTheFullLists() {
        assertEquals(service.retrieveCalculationTypes(), service.retrieveCalculationTypes(ChargeAppliesTo.LOAN, null));
        assertEquals(service.retrieveCollectionTimeTypes(), service.retrieveCollectionTimeTypes(ChargeAppliesTo.SAVINGS));
        assertEquals(service.retrievePaymentModes(), service.retrievePaymentModes(ChargeAppliesTo.SHARES));
        // null appliesTo also takes the full-list path
        assertEquals(service.retrieveCollectionTimeTypes(), service.retrieveCollectionTimeTypes(null));
    }
}
