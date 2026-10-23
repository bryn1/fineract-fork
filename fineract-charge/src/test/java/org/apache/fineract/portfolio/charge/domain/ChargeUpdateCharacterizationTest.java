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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.apache.fineract.portfolio.charge.data.ChargeData;
import org.apache.fineract.portfolio.charge.exception.ChargeDueAtDisbursementCannotBePenaltyException;
import org.apache.fineract.portfolio.charge.exception.ChargeMustBePenaltyException;
import org.apache.fineract.portfolio.charge.exception.ChargeParameterUpdateNotSupportedException;
import org.apache.fineract.portfolio.paymenttype.domain.PaymentType;
import org.apache.fineract.portfolio.tax.domain.TaxGroup;
import org.junit.jupiter.api.Test;

/**
 * S4a characterization suite (MC 11183): pins the CURRENT observable behavior of the {@link Charge}
 * entity update/delete/toData surface at pin 355287c8 — what IS, not what should be. Complements the
 * existing ChargeTest (payment-mode cases). Suspect pins are labeled; rows in research/04 "S4 suspect map".
 */
class ChargeUpdateCharacterizationTest {

    private static JsonCommand command(final String json) {
        return JsonCommand.from(json, com.google.gson.JsonParser.parseString(json), new FromJsonHelper(), "charges", 1L, null, null, null,
                null, null, null, null, null, null, null, null, null);
    }

    private static Charge charge(final String body) {
        return Charge.fromJson(command("{" + body + "}"), null, null, null);
    }

    private static Charge savingsWithdrawalFee() {
        return charge("\"name\": \"sw-fee\", \"amount\": 20, \"currencyCode\": \"USD\", \"chargeAppliesTo\": 2,"
                + " \"chargeTimeType\": 5, \"chargeCalculationType\": 1, \"penalty\": false, \"active\": true, \"locale\": \"en\"");
    }

    private static Charge loanDisbursementCharge() {
        return charge("\"name\": \"loan-disb\", \"amount\": 20, \"currencyCode\": \"USD\", \"chargeAppliesTo\": 1,"
                + " \"chargeTimeType\": 1, \"chargeCalculationType\": 1, \"chargePaymentMode\": 0, \"penalty\": false,"
                + " \"active\": true, \"locale\": \"en\"");
    }

    private static Charge workingCapitalCharge(final int timeType) {
        return charge("\"name\": \"wc-" + timeType + "\", \"amount\": 20, \"currencyCode\": \"USD\", \"chargeAppliesTo\": 5,"
                + " \"chargeTimeType\": " + timeType + ", \"chargeCalculationType\": 1, \"penalty\": false, \"active\": true,"
                + " \"locale\": \"en\"");
    }

    @Test
    void updateCountFrequencyTypeIsConvertedThroughChargeTimeTypeWhileCreateUsesPeriodFrequencyType() {
        // CHARACTERIZED (suspect — S4 suspect map S-C10): the create path stores countFrequencyType via
        // PeriodFrequencyType.fromInt, but the UPDATE branch converts the same wire parameter through
        // ChargeTimeType.fromInt. A wire value outside BOTH domains (99) therefore lands on 0 via update
        // (ChargeTimeType.INVALID) but on 5 via create (PeriodFrequencyType.INVALID) — 0 reads downstream
        // as DAYS, 5 as INVALID. Also applied(0) != reported(99).
        Charge c = charge("\"name\": \"sw-fw\", \"amount\": 5, \"currencyCode\": \"USD\", \"chargeAppliesTo\": 2,"
                + " \"chargeTimeType\": 5, \"chargeCalculationType\": 1, \"chargePaymentMode\": 0, \"penalty\": false,"
                + " \"active\": true, \"enableFreeWithdrawalCharge\": true, \"freeWithdrawalFrequency\": 5,"
                + " \"restartCountFrequency\": 5, \"countFrequencyType\": 2, \"locale\": \"en\"");
        assertEquals(Integer.valueOf(2), c.toData().getRestartFrequencyEnum());

        final Map<String, Object> changes = c.update(command("{\"countFrequencyType\": 99, \"locale\": \"en\"}"));
        assertEquals(99, changes.get("countFrequencyType"), "the wire value is reported verbatim");
        assertEquals(Integer.valueOf(0), c.toData().getRestartFrequencyEnum(),
                "but the applied value went through ChargeTimeType.fromInt(99)=INVALID=0");

        Charge fresh = charge("\"name\": \"sw-fw2\", \"amount\": 5, \"currencyCode\": \"USD\", \"chargeAppliesTo\": 2,"
                + " \"chargeTimeType\": 5, \"chargeCalculationType\": 1, \"chargePaymentMode\": 0, \"penalty\": false,"
                + " \"active\": true, \"enableFreeWithdrawalCharge\": true, \"freeWithdrawalFrequency\": 5,"
                + " \"restartCountFrequency\": 5, \"countFrequencyType\": 99, \"locale\": \"en\"");
        assertEquals(Integer.valueOf(5), fresh.toData().getRestartFrequencyEnum(),
                "create stored the same wire value through PeriodFrequencyType.fromInt(99)=INVALID=5");
    }

    @Test
    void updateAddingFeeOnMonthDayToAChargeWithoutOneFailsWithNpe() {
        // CHARACTERIZED (suspect — S4 suspect map S-C12): the feeOnMonthDay branch dereferences this.feeOnDay
        // unguarded; charges created without a month-day (loan charges, savings withdrawal fees) carry null
        // feeOnDay/feeOnMonth, so ADDING the value NPEs instead of applying it.
        Charge c = loanDisbursementCharge();
        assertThrows(NullPointerException.class,
                () -> c.update(command("{\"feeOnMonthDay\": \"15-01\", \"monthDayFormat\": \"dd-MM\", \"locale\": \"en\"}")));
    }

    @Test
    void updateRejectsAnyChangeToChargeAppliesToButToleratesTheSameValue() {
        Charge c = loanDisbursementCharge();
        assertThrows(ChargeParameterUpdateNotSupportedException.class,
                () -> c.update(command("{\"chargeAppliesTo\": 2, \"locale\": \"en\"}")));
        final Map<String, Object> unchanged = c.update(command("{\"chargeAppliesTo\": 1, \"locale\": \"en\"}"));
        assertFalse(unchanged.containsKey("chargeAppliesTo"));
    }

    @Test
    void updateEnforcesWorkingCapitalTimeCalculationPairingAtTheEntity() {
        // DISBURSEMENT WCL charge may move to PERCENT_OF_AMOUNT; SPECIFIED_DUE_DATE may not
        Charge disb = workingCapitalCharge(1);
        Map<String, Object> changes = disb.update(command("{\"chargeCalculationType\": 2, \"locale\": \"en\"}"));
        assertEquals(2, changes.get("chargeCalculationType"));

        Charge sdd = workingCapitalCharge(2);
        assertThrows(PlatformApiDataValidationException.class,
                () -> sdd.update(command("{\"chargeCalculationType\": 2, \"locale\": \"en\"}")));
    }

    @Test
    void penaltyTimeCrossGuardsRunForNonWorkingCapitalChargesOnly() {
        Charge disb = loanDisbursementCharge();
        assertThrows(ChargeDueAtDisbursementCannotBePenaltyException.class,
                () -> disb.update(command("{\"penalty\": true, \"locale\": \"en\"}")));

        Charge c = charge("\"name\": \"loan-sdd\", \"amount\": 20, \"currencyCode\": \"USD\", \"chargeAppliesTo\": 1,"
                + " \"chargeTimeType\": 2, \"chargeCalculationType\": 1, \"chargePaymentMode\": 0, \"penalty\": false,"
                + " \"active\": true, \"locale\": \"en\"");
        assertThrows(ChargeMustBePenaltyException.class, () -> c.update(command("{\"chargeTimeType\": 9, \"locale\": \"en\"}")));

        // the same flip on a WCL disbursement charge is allowed — the guards exempt WCL
        Charge wcl = workingCapitalCharge(1);
        Map<String, Object> changes = wcl.update(command("{\"penalty\": true, \"locale\": \"en\"}"));
        assertEquals(Boolean.TRUE, changes.get("penalty"));
    }

    @Test
    void updateRequiresFeeIntervalWhenFeeFrequencyIsSetAtTheEntity() {
        Charge c = loanDisbursementCharge();
        assertThrows(PlatformApiDataValidationException.class,
                () -> c.update(command("{\"feeFrequency\": 2, \"locale\": \"en\"}")));

        Map<String, Object> changes = loanDisbursementCharge()
                .update(command("{\"feeFrequency\": 2, \"feeInterval\": 3, \"locale\": \"en\"}"));
        assertEquals(2, changes.get("feeFrequency"));
        assertEquals(3, changes.get("feeInterval"));
    }

    @Test
    void aRejectedUpdateHasAlreadyMutatedTheEntityInMemory() {
        // CHARACTERIZED (suspect — S4 suspect map S-C15): update() applies every field BEFORE the trailing
        // validation throw — after the rejection above this.feeFrequency already holds 2 (proven here by a
        // repeat of the same update reporting NO change). Production safety rests entirely on the caller's
        // @Transactional rollback / entity eviction, not on the entity itself.
        Charge c = loanDisbursementCharge();
        assertThrows(PlatformApiDataValidationException.class,
                () -> c.update(command("{\"feeFrequency\": 2, \"locale\": \"en\"}")));
        Map<String, Object> followUp = c.update(command("{\"feeInterval\": 3, \"locale\": \"en\"}"));
        assertEquals(3, followUp.get("feeInterval"));
        assertFalse(followUp.containsKey("feeFrequency"),
                "the rejected feeFrequency=2 was applied BEFORE the throw: the follow-up sees it as no change");
    }

    @Test
    void updateAttachesTaxGroupWhenAbsentButRejectsReplacementOnceAttached() {
        Charge c = savingsWithdrawalFee();
        Map<String, Object> attach = c.update(command("{\"taxGroupId\": 7, \"locale\": \"en\"}"));
        assertEquals(7L, attach.get("taxGroupId"), "attaching a first tax group is reported and reaches the service resolver");

        TaxGroup existing = TaxGroup.createTaxGroup("tg", Set.of());
        existing.setId(3L);
        Charge attached = Charge.fromJson(command("{\"name\": \"sw-tg\", \"amount\": 5, \"currencyCode\": \"USD\","
                + " \"chargeAppliesTo\": 2, \"chargeTimeType\": 5, \"chargeCalculationType\": 1, \"penalty\": false,"
                + " \"active\": true, \"locale\": \"en\"}"), null, existing, null);
        PlatformApiDataValidationException ex = assertThrows(PlatformApiDataValidationException.class,
                () -> attached.update(command("{\"taxGroupId\": 8, \"locale\": \"en\"}")));
        assertTrue(ex.getErrors().stream().anyMatch(e -> "taxGroupId".equals(e.getParameterName())),
                "replacement of an existing tax group is refused, got: " + ex.getErrors());
    }

    @Test
    void updatePaymentTypeIdToNullIsReportedButNeverApplied() {
        // CHARACTERIZED (suspect — S4 suspect map S-C13): the change map gets paymentTypeId=null (the entity
        // only records, the service applies later) but the SERVICE applies only non-null values, so a null
        // paymentTypeId is reported as changed while the payment type stays attached — applied != reported,
        // and removing a payment type is impossible through this API.
        PaymentType paymentType = new PaymentType();
        paymentType.setId(9L);
        paymentType.setName("cash");
        Charge c = Charge.fromJson(command("{\"name\": \"sw-pt\", \"amount\": 5, \"currencyCode\": \"USD\","
                + " \"chargeAppliesTo\": 2, \"chargeTimeType\": 5, \"chargeCalculationType\": 1, \"chargePaymentMode\": 0,"
                + " \"penalty\": false, \"active\": true, \"enablePaymentType\": true, \"locale\": \"en\"}"), null, null,
                paymentType);
        assertEquals(9L, c.toData().getPaymentTypeOptions().getId());

        Map<String, Object> changes = c.update(command("{\"paymentTypeId\": null, \"locale\": \"en\"}"));
        assertTrue(changes.containsKey("paymentTypeId"), "the removal attempt is reported in the change map");
        assertNull(changes.get("paymentTypeId"));
        assertEquals(9L, c.toData().getPaymentTypeOptions().getId(), "the entity never detaches the payment type");
    }

    @Test
    void deleteIsASoftDeleteThatDecoratesTheNameWithTheId() {
        Charge c = savingsWithdrawalFee();
        c.setId(42L);
        c.delete();
        assertTrue(c.isDeleted());
        assertEquals("42_sw-fee", c.getName());
    }

    @Test
    void toDataRendersACodeOnlyCurrencyStubAndResolvesEveryEnumToItsOptionData() {
        // CHARACTERIZED (suspect — S4 suspect map S-C11, P4): toData hardcodes CurrencyData(code, null, 0, 0,
        // null, null) — name/decimal-places/multiples-of/display-symbol never ride along, so services that
        // build API JSON through toData publish decimalPlaces=0 regardless of the real currency.
        // caps are stored only for PERCENT_OF_AMOUNT charges (entity create AND update gate them)
        Charge c = charge("\"name\": \"sw-data\", \"amount\": 20, \"currencyCode\": \"USD\", \"chargeAppliesTo\": 2,"
                + " \"chargeTimeType\": 5, \"chargeCalculationType\": 2, \"chargePaymentMode\": 0, \"penalty\": true,"
                + " \"active\": false, \"minCap\": 1.5, \"maxCap\": 9.5, \"locale\": \"en\"");
        ChargeData data = c.toData();
        assertEquals("USD", data.getCurrency().getCode());
        assertNull(data.getCurrency().getName());
        assertEquals(Integer.valueOf(0), data.getCurrency().getDecimalPlaces());
        assertEquals("Withdrawal Fee", data.getChargeTimeType().getValue());
        assertEquals("Savings", data.getChargeAppliesTo().getValue());
        assertEquals("% Amount", data.getChargeCalculationType().getValue());
        assertEquals("Regular", data.getChargePaymentMode().getValue());
        assertEquals(new BigDecimal("1.5"), data.getMinCap());
        assertEquals(new BigDecimal("9.5"), data.getMaxCap());
        assertTrue(data.isPenalty());
        assertFalse(data.isActive());
        assertNull(data.getFeeFrequency());
        assertNull(data.getIncomeOrLiabilityAccount());
        assertNull(data.getTaxGroup());
    }

    @Test
    void toDataThrowsNpeForAnyChargeCreatedWithoutAPaymentMode() {
        // CHARACTERIZED (suspect — S4 suspect map S-C14): toData() unboxes this.chargePaymentMode directly;
        // create legitimately leaves it NULL for savings/client/share charges (the deserializer only demands
        // it for loans), so every DTO built through toData for those charges is an NPE landmine at the seam.
        Charge c = savingsWithdrawalFee();
        assertThrows(NullPointerException.class, c::toData);
    }
}
