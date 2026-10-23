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
package org.apache.fineract.portfolio.charge.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import org.apache.fineract.infrastructure.core.data.EnumOptionData;
import org.apache.fineract.organisation.monetary.data.CurrencyData;
import org.apache.fineract.portfolio.charge.data.ChargeData;
import org.apache.fineract.portfolio.savings.data.SavingsAccountChargeData;
import org.apache.fineract.portfolio.shareaccounts.data.ShareAccountChargeData;
import org.junit.jupiter.api.Test;

/**
 * S4a characterization suite (MC 11183): pins the CURRENT field-carrying behavior of the provider-resident
 * {@link ConvertChargeDataToSpecificChargeData} at pin 355287c8 (pure function, zero DB). Suspect pins are
 * labeled; rows in research/04 "S4 suspect map".
 */
class ConvertChargeDataToSpecificChargeDataCharacterizationTest {

    private static ChargeData definition() {
        return ChargeData.builder().id(7L).name("Wire fee").amount(new BigDecimal("12.50"))
                .currency(new CurrencyData("USD", "US Dollar", 2, 0, "$", "USD.code"))
                .chargeTimeType(new EnumOptionData(5L, "chargeTimeType.withdrawalFee", "Withdrawal Fee"))
                .chargeCalculationType(new EnumOptionData(1L, "chargeCalculationType.flat", "Flat"))
                .penalty(true).active(true).freeWithdrawal(true).freeWithdrawalChargeFrequency(4).restartFrequency(2)
                .restartFrequencyEnum(1).feeInterval(3).feeOnMonthDay(java.time.MonthDay.of(1, 15)).build();
    }

    @Test
    void toSavingsAccountChargeDataCarriesDefinitionIdentityAndZeroesEveryRunStateField() {
        SavingsAccountChargeData mapped = ConvertChargeDataToSpecificChargeData.toSavingsAccountChargeData(definition());

        // identity carried from the definition
        assertNull(mapped.getId(), "the instance id is never set — only chargeId carries the definition id");
        assertEquals(Long.valueOf(7L), mapped.getChargeId(), "the definition id travels as chargeId; the instance id stays null");
        assertNull(mapped.getAccountId());
        assertEquals("Wire fee", mapped.getName());
        assertEquals("USD", mapped.getCurrency().getCode());
        assertEquals(new BigDecimal("12.50"), mapped.getAmount());
        assertEquals("Withdrawal Fee", mapped.getChargeTimeType().getValue());
        assertEquals("Flat", mapped.getChargeCalculationType().getValue());
        assertTrue(mapped.isPenalty());
        assertEquals(java.time.MonthDay.of(1, 15), mapped.getFeeOnMonthDay());
        assertEquals(Integer.valueOf(3), mapped.getFeeInterval());

        // every run-state field is hardcoded: zeros for amounts, nulls for ids/flags
        assertEquals(BigDecimal.ZERO, mapped.getAmountPaid());
        assertEquals(BigDecimal.ZERO, mapped.getAmountWaived());
        assertEquals(BigDecimal.ZERO, mapped.getAmountOutstanding());
        assertEquals(BigDecimal.ZERO, mapped.getPercentage());
        assertNull(mapped.getDueDate());
        assertNull(mapped.getIsActive());
        assertNull(mapped.getInactivationDate());
    }

    @Test
    void toSavingsAccountChargeDataDropsTheFreeWithdrawalFactsPresentOnTheSource() {
        // CHARACTERIZED (suspect — S4 suspect map S-U1): the source ChargeData carries freeWithdrawal=true
        // with frequency/restart values, yet the conversion hardcodes all three to null — a caller building
        // a savings-charge view loses the free-withdrawal configuration even when the definition has it.
        SavingsAccountChargeData mapped = ConvertChargeDataToSpecificChargeData.toSavingsAccountChargeData(definition());

        assertNull(mapped.getIsFreeWithdrawal(), "isFreeWithdrawal is nulled out even though the source has it");
        assertNull(mapped.getFreeWithdrawalChargeFrequency());
        assertNull(mapped.getRestartFrequency());
        assertNull(mapped.getRestartFrequencyEnum());
    }

    @Test
    void toShareAccountChargeDataCarriesIdentityAndZeroesRunState() {
        ShareAccountChargeData mapped = ConvertChargeDataToSpecificChargeData.toShareAccountChargeData(definition());

        assertNull(mapped.getId(), "the instance id is never set — only chargeId carries the definition id");
        assertEquals(Long.valueOf(7L), mapped.getChargeId());
        assertNull(mapped.getAccountId());
        assertEquals("Wire fee", mapped.getName());
        assertEquals(new BigDecimal("12.50"), mapped.getAmount());
        assertEquals("Withdrawal Fee", mapped.getChargeTimeType().getValue());
        assertEquals("Flat", mapped.getChargeCalculationType().getValue());
        assertEquals(BigDecimal.ZERO, mapped.getAmountPaid());
        assertEquals(BigDecimal.ZERO, mapped.getAmountOutstanding());
        assertNull(mapped.getIsActive());
        assertEquals(BigDecimal.ZERO, mapped.getAmountOrPercentage());
    }
}
