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
package org.apache.fineract.portfolio.charge.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * S4a characterization suite (MC 11183): pins the CURRENT public wire-constant and request-DTO surface of
 * the charge module at pin 355287c8 (interface-freeze guard: these literals and field types ARE the public
 * contract; a change here without a decision row is a contract break, not a refactor).
 */
class ChargesApiSurfaceCharacterizationTest {

    @Test
    void apiParameterNameLiteralsArePinned() {
        // ChargesApiConstants feeds the deserializer's SUPPORTED_PARAMETERS set and the write service's
        // command readers — the literal strings are the public JSON contract
        assertEquals("incomeAccountId", ChargesApiConstants.glAccountIdParamName);
        assertEquals("taxGroupId", ChargesApiConstants.taxGroupIdParamName);
    }

    @Test
    void deserializerParameterNameConstantsMatchTheirLiterals() {
        // duplicated as public constants on the deserializer itself (public surface of the module) and
        // consumed verbatim by validation code paths — pinned against accidental renames
        assertEquals("name", org.apache.fineract.portfolio.charge.serialization.ChargeDefinitionCommandFromApiJsonDeserializer.NAME);
        assertEquals("chargeAppliesTo",
                org.apache.fineract.portfolio.charge.serialization.ChargeDefinitionCommandFromApiJsonDeserializer.CHARGE_APPLIES_TO);
        assertEquals("enableFreeWithdrawalCharge",
                org.apache.fineract.portfolio.charge.serialization.ChargeDefinitionCommandFromApiJsonDeserializer.ENABLE_FREE_WITHDRAWAL_CHARGE);
        assertEquals("paymentTypeId",
                org.apache.fineract.portfolio.charge.serialization.ChargeDefinitionCommandFromApiJsonDeserializer.PAYMENT_TYPE_ID);
    }

    @Test
    void chargeRequestFieldSurfaceIsPinned() throws Exception {
        // the ChargeRequest DTO (public request surface) — exact declared instance fields and their types
        String actual = Arrays.stream(org.apache.fineract.portfolio.charge.request.ChargeRequest.class.getDeclaredFields())
                .filter(f -> !f.isSynthetic() && !java.lang.reflect.Modifier.isStatic(f.getModifiers()))
                .map(f -> f.getType().getSimpleName() + " " + f.getName()).sorted().collect(Collectors.joining(";"));
        assertEquals("BigDecimal maxCap;BigDecimal minCap;Boolean active;Boolean enablePaymentType;Boolean penalty;Double amount;"
                + "Integer chargeAppliesTo;Integer chargeCalculationType;Integer chargePaymentMode;Integer chargeTimeType;"
                + "Long incomeAccountId;Long paymentTypeId;Long taxGroupId;String currencyCode;String feeFrequency;String feeInterval;"
                + "String feeOnMonthDay;String locale;String monthDayFormat;String name", actual);
    }
}
